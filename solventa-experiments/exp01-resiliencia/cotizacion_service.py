"""
EXP-01 | Servicio de Cotizacion con Circuit Breaker + Cache.

Incorpora las tres correcciones validadas en la semana 6:
  D-01: el presupuesto de 120 ms es efectivo en el camino del usuario
        (el timeout duro de 700 ms queda reservado al sondeo).
  D-02: minimo de 5 llamadas para evaluar la apertura del circuito,
        de modo que tambien protege con trafico bajo.
  D-03: el sondeo de recuperacion corre en segundo plano.

Ejecutar:  uvicorn cotizacion_service:app --host 0.0.0.0 --port 8080
"""
import os
import time
import json
import asyncio
import logging

import httpx
import redis.asyncio as redis
from fastapi import FastAPI
from pydantic import BaseModel

log = logging.getLogger("cotizacion")
logging.basicConfig(level=logging.INFO)

OPEN_FINANCE_URL = os.getenv("OPEN_FINANCE_URL", "http://wiremock:8080/perfil")
REDIS_URL = os.getenv("REDIS_URL", "redis://localhost:6379")
BUDGET_MS = int(os.getenv("BUDGET_MS", "120"))            # D-01
HARD_TIMEOUT_MS = int(os.getenv("HARD_TIMEOUT_MS", "700"))  # solo para el sondeo
FAIL_THRESHOLD = float(os.getenv("FAIL_THRESHOLD", "0.10"))
WINDOW_S = int(os.getenv("WINDOW_S", "30"))
MIN_CALLS = int(os.getenv("MIN_CALLS", "5"))              # D-02
PROBE_AFTER_S = int(os.getenv("PROBE_AFTER_S", "10"))
CACHE_TTL_S = int(os.getenv("CACHE_TTL_S", "86400"))

app = FastAPI(title="Solventa - Cotizacion (EXP-01)")


class CircuitBreaker:
    """Estados: CLOSED -> OPEN -> (sondeo en segundo plano) -> CLOSED."""

    def __init__(self):
        self.state = "CLOSED"
        self.events = []
        self.opened_at = None
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
                    log.warning("Circuito ABIERTO (%.0f%% de fallos en ventana)",
                                100 * fails / len(self.events))

    async def maybe_probe(self, client: httpx.AsyncClient):
        """D-03: el sondeo de recuperacion no bloquea al usuario."""
        async with self.lock:
            if self.state != "OPEN" or self.probing:
                return
            if time.time() - self.opened_at < PROBE_AFTER_S:
                return
            self.probing = True
        asyncio.create_task(self._probe(client))

    async def _probe(self, client: httpx.AsyncClient):
        ok = False
        try:
            r = await client.get(OPEN_FINANCE_URL,
                                 params={"cliente": "__probe__"},
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
                log.info("Circuito CERRADO tras sondeo exitoso")
            else:
                self.opened_at = time.time()


cb = CircuitBreaker()
_client: httpx.AsyncClient = None
_cache: redis.Redis = None


@app.on_event("startup")
async def startup():
    global _client, _cache
    _client = httpx.AsyncClient()
    _cache = redis.from_url(REDIS_URL, decode_responses=True)


@app.on_event("shutdown")
async def shutdown():
    await _client.aclose()
    await _cache.aclose()


class CotizacionReq(BaseModel):
    cliente: str
    producto: str = "vida-hipotecario"
    monto: float = 100_000_000


async def obtener_perfil(cliente: str):
    """Devuelve (perfil, origen). origen in {proveedor, cache, defecto}."""
    if await cb.allow():
        try:
            r = await _client.get(OPEN_FINANCE_URL,
                                  params={"cliente": cliente},
                                  timeout=BUDGET_MS / 1000)    # D-01
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
    factor = 1.0 + max(0, (700 - score)) / 1000.0
    return round(req.monto * 0.0008 * factor, 2)


@app.post("/cotizar")
async def cotizar(req: CotizacionReq):
    t0 = time.perf_counter()
    perfil, origen = await obtener_perfil(req.cliente)
    prima = calcular_prima(perfil, req)
    ms = (time.perf_counter() - t0) * 1000
    return {
        "cliente": req.cliente,
        "producto": req.producto,
        "prima": prima,
        "origen_perfil": origen,
        "degradado": origen != "proveedor",
        "latencia_ms": round(ms, 2),
    }


@app.get("/health")
async def health():
    return {"status": "ok", "circuito": cb.state, "metrics": cb.metrics}
