"""
EXP-04 | Auditoria inmutable y propagacion de revocacion de consentimiento.

Incorpora la correccion D-04 validada en la semana 6:
  la expiracion pasiva por TTL NO basta como mecanismo de revocacion
  (dejaba el dato accesible hasta 300 s). La invalidacion explicita por
  evento es obligatoria.

Ejecutar: uvicorn consentimiento_service:app --host 0.0.0.0 --port 8082
"""
import os
import json
import time
import uuid
import logging

import redis.asyncio as redis
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from google.cloud import bigquery, pubsub_v1

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("consentimiento")

PROJECT_ID = os.environ.get("GCP_PROJECT_ID", "")
REDIS_URL = os.getenv("REDIS_URL", "redis://localhost:6379")
DATASET = os.getenv("AUDIT_DATASET", "solventa_auditoria")
TABLA = os.getenv("AUDIT_TABLE", "decisiones")
TOPIC_REVOCACION = os.getenv("TOPIC_REVOCACION", "consentimiento-revocado")

app = FastAPI(title="Solventa - Consentimiento y Auditoria (EXP-04)")

_cache: redis.Redis = None
_bq: bigquery.Client = None
_pub: pubsub_v1.PublisherClient = None
_topic_path = None


@app.on_event("startup")
async def startup():
    global _cache, _bq, _pub, _topic_path
    _cache = redis.from_url(REDIS_URL, decode_responses=True)
    if PROJECT_ID:
        _bq = bigquery.Client(project=PROJECT_ID)
        _pub = pubsub_v1.PublisherClient()
        _topic_path = _pub.topic_path(PROJECT_ID, TOPIC_REVOCACION)


# ----------------------------------------------------------------------
# Auditoria inmutable
# ----------------------------------------------------------------------
class DecisionAudit(BaseModel):
    cliente: str
    version_regla: str
    variables_entrada: dict
    consentimiento_id: str
    prima: float


async def registrar_decision(d: DecisionAudit) -> str:
    """Escritura append-only. Se invoca de forma ASINCRONA desde el motor de
    tarificacion: medido en la semana 6, ese modo agrega 0,01 ms al recorrido,
    frente a 0,81 ms del modo sincrono."""
    id_decision = str(uuid.uuid4())
    fila = {
        "id_decision": id_decision,
        "cliente": d.cliente,
        "version_regla": d.version_regla,
        "variables_entrada": json.dumps(d.variables_entrada),
        "consentimiento_id": d.consentimiento_id,
        "prima": d.prima,
        "ts": time.strftime("%Y-%m-%d %H:%M:%S", time.gmtime()),
    }
    if _bq:
        errores = _bq.insert_rows_json(f"{PROJECT_ID}.{DATASET}.{TABLA}", [fila])
        if errores:
            raise RuntimeError(f"fallo al registrar la auditoria: {errores}")
    return id_decision


@app.post("/auditoria/decision")
async def post_decision(d: DecisionAudit):
    return {"id_decision": await registrar_decision(d)}


@app.get("/auditoria/decision/{id_decision}")
async def reconstruir(id_decision: str):
    """Reconstruye una decision para actuaria o el regulador (RC-08)."""
    if not _bq:
        raise HTTPException(503, "BigQuery no configurado")
    q = f"""
        SELECT id_decision, cliente, version_regla, variables_entrada,
               consentimiento_id, prima, ts
        FROM `{PROJECT_ID}.{DATASET}.{TABLA}`
        WHERE id_decision = @id
    """
    cfg = bigquery.QueryJobConfig(query_parameters=[
        bigquery.ScalarQueryParameter("id", "STRING", id_decision)])
    filas = list(_bq.query(q, job_config=cfg).result())
    if not filas:
        raise HTTPException(404, "decision no encontrada")
    f = dict(filas[0])
    f["variables_entrada"] = json.loads(f["variables_entrada"])
    f["reconstruccion_completa"] = all(
        f.get(k) for k in ("cliente", "version_regla", "variables_entrada", "consentimiento_id"))
    return f


# ----------------------------------------------------------------------
# Consentimiento y revocacion  (correccion D-04)
# ----------------------------------------------------------------------
class Revocacion(BaseModel):
    cliente: str
    consentimiento_id: str


@app.post("/consentimiento/revocar")
async def revocar(r: Revocacion):
    """D-04: invalidacion EXPLICITA e inmediata, no expiracion pasiva.

    1) Marca el consentimiento como revocado.
    2) Borra el perfil de la cache del propio servicio.
    3) Publica el evento para que TODOS los consumidores invaliden su copia.
    """
    t0 = time.perf_counter()

    await _cache.setex(f"revocado:{r.cliente}", 86400 * 365, r.consentimiento_id)
    borradas = await _cache.delete(f"perfil:{r.cliente}")

    publicado = False
    if _pub:
        payload = json.dumps({
            "tipo": "consentimiento_revocado",
            "cliente": r.cliente,
            "consentimiento_id": r.consentimiento_id,
            "ts": time.time(),
        }).encode("utf-8")
        _pub.publish(_topic_path, payload).result(timeout=10)
        publicado = True

    ms = (time.perf_counter() - t0) * 1000
    log.info("revocacion de %s propagada en %.2f ms", r.cliente, ms)
    return {
        "cliente": r.cliente,
        "entradas_cache_borradas": borradas,
        "evento_publicado": publicado,
        "propagacion_ms": round(ms, 2),
        "meta_rc08_ms": 300_000,          # 5 minutos
        "cumple": ms < 300_000,
    }


@app.get("/consentimiento/{cliente}/vigente")
async def vigente(cliente: str):
    revocado = await _cache.get(f"revocado:{cliente}")
    return {"cliente": cliente, "vigente": revocado is None}
