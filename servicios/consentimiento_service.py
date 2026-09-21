"""
Servicio de Consentimiento y Auditoria (EXP-04).

Revocacion (correccion D-04): invalidacion EXPLICITA por evento.
  1. Registra la revocacion en Redis (la ven de inmediato quienes usan la cache compartida).
  2. Borra el perfil de la cache compartida.
  3. Publica el evento para los consumidores con copia propia (p. ej. perfilamiento).

Reconstruccion: lee del almacen append-only en Postgres.
"""
import os
import json
import time
import logging

import psycopg2
import psycopg2.extras
import redis
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

import bus

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("consentimiento")

REDIS_URL = os.getenv("REDIS_URL", "redis://localhost:6379")
PG_DSN = os.getenv("PG_DSN", "")
TOPIC = os.getenv("TOPIC_REVOCACION", "consentimiento-revocado")

app = FastAPI(title="Solventa - Consentimiento y Auditoria")
r = redis.from_url(REDIS_URL, decode_responses=True)


class Revocacion(BaseModel):
    cliente: str
    consentimiento_id: str


@app.post("/consentimiento/revocar")
def revocar(rv: Revocacion):
    t0 = time.perf_counter()
    r.set(f"revocado:{rv.cliente}", rv.consentimiento_id)
    borradas = r.delete(f"perfil:{rv.cliente}")
    bus.publicar(TOPIC, {"tipo": "consentimiento_revocado", "cliente": rv.cliente,
                         "consentimiento_id": rv.consentimiento_id, "ts": time.time()})
    ms = (time.perf_counter() - t0) * 1000
    log.info("revocacion de %s publicada en %.2f ms", rv.cliente, ms)
    return {"cliente": rv.cliente, "cache_borrada": bool(borradas),
            "publicacion_ms": round(ms, 2)}


@app.get("/auditoria/decision/{id_decision}")
def reconstruir(id_decision: str):
    with psycopg2.connect(PG_DSN) as cn, cn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
        cur.execute("""SELECT id_decision, cliente, version_regla, variables_entrada,
                              consentimiento_id, prima, origen_perfil, ts,
                              hash_anterior, hash_propio
                       FROM auditoria_decisiones WHERE id_decision = %s""", (id_decision,))
        fila = cur.fetchone()
    if not fila:
        raise HTTPException(404, "decision no encontrada")
    fila["reconstruccion_completa"] = all(
        fila.get(k) for k in ("cliente", "version_regla", "variables_entrada", "consentimiento_id"))
    return json.loads(json.dumps(fila, default=str))


@app.get("/health")
def health():
    return {"status": "ok", "bus": bus.BACKEND}
