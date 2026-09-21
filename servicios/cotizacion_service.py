"""
Servicio de Cotizacion. Participa en EXP-01 y EXP-04.

EXP-01  Circuit Breaker + cache, con las correcciones verificadas en la semana 6:
  D-01  El presupuesto de 120 ms es efectivo en el camino del usuario.
  D-02  Minimo de 5 llamadas para evaluar la apertura del circuito.
  D-03  El sondeo de recuperacion corre en segundo plano.

EXP-04  Consentimiento y auditoria:
  D-04  Si el cliente revoco, NO se consulta al proveedor ni se usa la cache:
        revocar significa dejar de usar el dato, no solo borrarlo.
  AUDIT_MODE=off|sync|async controla como se registra cada decision en el
  almacen append-only, para medir su costo en latencia.
"""
import os
import time
import json
import uuid
import queue
import asyncio
import logging
import threading

import httpx
import psycopg2
import psycopg2.pool
import redis.asyncio as aioredis
from fastapi import FastAPI
from pydantic import BaseModel

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("cotizacion")

OPEN_FINANCE_URL = os.getenv("OPEN_FINANCE_URL", "http://localhost:8090/perfil")
REDIS_URL = os.getenv("REDIS_URL", "redis://localhost:6379")
PG_DSN = os.getenv("PG_DSN", "")
AUDIT_MODE = os.getenv("AUDIT_MODE", "async")      # off | sync | async
VERSION_REGLA = os.getenv("VERSION_REGLA", "rating-v2.1")

BUDGET_MS = int(os.getenv("BUDGET_MS", "120"))              # D-01
HARD_TIMEOUT_MS = int(os.getenv("HARD_TIMEOUT_MS", "700"))  # solo el sondeo
FAIL_THRESHOLD = float(os.getenv("FAIL_THRESHOLD", "0.10"))
WINDOW_S = int(os.getenv("WINDOW_S", "30"))
MIN_CALLS = int(os.getenv("MIN_CALLS", "5"))                # D-02
PROBE_AFTER_S = int(os.getenv("PROBE_AFTER_S", "10"))
CACHE_TTL_S = int(os.getenv("CACHE_TTL_S", "86400"))

app = FastAPI(title="Solventa - Cotizacion")


# --------------------------------------------------------------------------
# Circuit Breaker
# --------------------------------------------------------------------------
class CircuitBreaker:
    def __init__(self):
        self.state = "CLOSED"
        self.events = []
        self.opened_at = 0.0
        self.probing = False
        self.lock = asyncio.Lock()
        self.metrics = {"aperturas": 0, "cierres": 0}

    async def allow(self) -> bool:
        async with self.lock:
            return self.state != "OPEN"

    async def record(self, ok: bool):
        async with self.lock:
            now = time.time()
            self.events.append((now, ok))
            self.events = [e for e in self.events if now - e[0] <= WINDOW_S]
            if self.state == "CLOSED" and len(self.events) >= MIN_CALLS:
                fails = sum(1 for _, o in self.events if not o)
                if fails / len(self.events) >= FAIL_THRESHOLD:
                    self.state = "OPEN"
                    self.opened_at = now
                    self.metrics["aperturas"] += 1
                    log.warning("circuito ABIERTO (%d/%d fallos)", fails, len(self.events))

    async def maybe_probe(self, client):
        async with self.lock:
            if self.state != "OPEN" or self.probing:
                return
            if time.time() - self.opened_at < PROBE_AFTER_S:
                return
            self.probing = True
        asyncio.create_task(self._probe(client))              # D-03

    async def _probe(self, client):
        ok = False
        try:
            r = await client.get(OPEN_FINANCE_URL, params={"cliente": "__probe__"},
                                 timeout=HARD_TIMEOUT_MS / 1000)
            ok = r.status_code == 200
        except Exception:
            ok = False
        async with self.lock:
            self.probing = False
            if ok:
                self.state = "CLOSED"
                self.events = []
                self.metrics["cierres"] += 1
                log.info("circuito CERRADO tras sondeo exitoso")
            else:
                self.opened_at = time.time()

    async def reset(self):
        async with self.lock:
            self.state, self.events, self.probing = "CLOSED", [], False


# --------------------------------------------------------------------------
# Auditoria append-only
# --------------------------------------------------------------------------
_pool = None
_audit_q: "queue.Queue" = queue.Queue(maxsize=100_000)
audit_metrics = {"escritas": 0, "errores": 0}

SQL_AUDIT = """INSERT INTO auditoria_decisiones
  (id_decision, cliente, version_regla, variables_entrada, consentimiento_id, prima, origen_perfil)
  VALUES (%s, %s, %s, %s, %s, %s, %s)"""


def _escribir(fila):
    cn = _pool.getconn()
    try:
        with cn.cursor() as cur:
            cur.execute(SQL_AUDIT, fila)
        cn.commit()
        audit_metrics["escritas"] += 1
    except Exception:
        cn.rollback()
        audit_metrics["errores"] += 1
        log.exception("fallo escribiendo auditoria")
    finally:
        _pool.putconn(cn)


def _escritor_async():
    while True:
        _escribir(_audit_q.get())


# --------------------------------------------------------------------------
cb = CircuitBreaker()
_client: httpx.AsyncClient = None
_cache: aioredis.Redis = None


@app.on_event("startup")
async def startup():
    global _client, _cache, _pool
    _client = httpx.AsyncClient()
    _cache = aioredis.from_url(REDIS_URL, decode_responses=True)
    if PG_DSN:
        _pool = psycopg2.pool.ThreadedConnectionPool(2, 20, PG_DSN)
        threading.Thread(target=_escritor_async, daemon=True).start()
    log.info("cotizacion lista | AUDIT_MODE=%s BUDGET_MS=%d MIN_CALLS=%d",
             AUDIT_MODE, BUDGET_MS, MIN_CALLS)


@app.on_event("shutdown")
async def shutdown():
    await _client.aclose()
    await _cache.aclose()


class CotizacionReq(BaseModel):
    cliente: str
    producto: str = "vida-hipotecario"
    monto: float = 100_000_000


async def obtener_perfil(cliente: str):
    """Devuelve (perfil, origen). origen: proveedor | cache | defecto | sin_consentimiento."""
    if await _cache.exists(f"revocado:{cliente}"):                       # D-04
        return {"score": 600, "fuente": "tarifa_estandar"}, "sin_consentimiento"

    if await cb.allow():
        try:
            r = await _client.get(OPEN_FINANCE_URL, params={"cliente": cliente},
                                  timeout=BUDGET_MS / 1000)              # D-01
            r.raise_for_status()
            perfil = r.json()
            await cb.record(True)
            await _cache.setex(f"perfil:{cliente}", CACHE_TTL_S, json.dumps(perfil))
            return perfil, "proveedor"
        except Exception:
            await cb.record(False)
    else:
        await cb.maybe_probe(_client)

    cached = await _cache.get(f"perfil:{cliente}")
    if cached:
        return json.loads(cached), "cache"
    return {"score": 600, "fuente": "valor_por_defecto"}, "defecto"


def calcular_prima(perfil: dict, req: CotizacionReq) -> float:
    score = perfil.get("score", 600)
    factor = 1.0 + max(0, 700 - score) / 1000.0
    return round(req.monto * 0.0008 * factor, 2)


@app.post("/cotizar")
async def cotizar(req: CotizacionReq):
    t0 = time.perf_counter()
    perfil, origen = await obtener_perfil(req.cliente)
    prima = calcular_prima(perfil, req)
    id_decision = str(uuid.uuid4())

    if _pool and AUDIT_MODE != "off":
        fila = (id_decision, req.cliente, VERSION_REGLA,
                json.dumps({"producto": req.producto, "monto": req.monto,
                            "score": perfil.get("score")}),
                f"cons-{req.cliente}", prima, origen)
        if AUDIT_MODE == "sync":
            await asyncio.get_running_loop().run_in_executor(None, _escribir, fila)
        else:
            try:
                _audit_q.put_nowait(fila)
            except queue.Full:
                audit_metrics["errores"] += 1

    ms = (time.perf_counter() - t0) * 1000
    return {"id_decision": id_decision, "cliente": req.cliente, "prima": prima,
            "origen_perfil": origen, "degradado": origen not in ("proveedor", "sin_consentimiento"),
            "latencia_ms": round(ms, 2)}


@app.get("/health")
async def health():
    return {"status": "ok", "circuito": cb.state, "metrics": cb.metrics,
            "audit_mode": AUDIT_MODE, "audit": audit_metrics,
            "audit_pendientes": _audit_q.qsize()}


class ModoAudit(BaseModel):
    modo: str


@app.post("/admin/audit_mode")
async def cambiar_modo(m: ModoAudit):
    """Permite medir off / sync / async en la misma instancia (EXP-04 Parte A)."""
    global AUDIT_MODE
    if m.modo not in ("off", "sync", "async"):
        return {"error": "modo debe ser off, sync o async"}
    AUDIT_MODE = m.modo
    return {"audit_mode": AUDIT_MODE}


@app.post("/admin/reset")
async def reset():
    """Reinicia el circuito entre escenarios para que cada corrida sea independiente."""
    await cb.reset()
    return {"circuito": cb.state}
