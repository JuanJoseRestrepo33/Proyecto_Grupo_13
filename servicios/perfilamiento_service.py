"""
Servicio de Perfilamiento (EXP-04). Es un consumidor del dato de Open Finance
que mantiene su PROPIA copia en memoria, distinta de la cache compartida.

Por eso es la prueba real de la correccion D-04: este servicio no ve la cache
de Redis que borra el servicio de consentimiento; solo se entera de la
revocacion porque recibe el evento. Si el evento no llega, sigue sirviendo el
dato de un cliente que ya retiro su consentimiento.
"""
import os
import time
import logging
import threading

import httpx
from fastapi import FastAPI

import bus

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("perfilamiento")

OPEN_FINANCE_URL = os.getenv("OPEN_FINANCE_URL", "http://localhost:8090/perfil")
TOPIC = os.getenv("TOPIC_REVOCACION", "consentimiento-revocado")
SUBSCRIPTION = os.getenv("SUB_REVOCACION", "consentimiento-revocado-perfilamiento")

app = FastAPI(title="Solventa - Perfilamiento")
_cache_local: dict = {}
_revocados: set = set()
_lock = threading.Lock()
metricas = {"eventos_recibidos": 0, "ultimo_evento_lag_ms": None}


def on_revocacion(evento: dict):
    cliente = evento["cliente"]
    with _lock:
        _revocados.add(cliente)
        _cache_local.pop(cliente, None)
    metricas["eventos_recibidos"] += 1
    if "ts" in evento:
        metricas["ultimo_evento_lag_ms"] = round((time.time() - evento["ts"]) * 1000, 2)
    log.info("revocacion aplicada para %s", cliente)


@app.on_event("startup")
async def startup():
    bus.suscribir(TOPIC, SUBSCRIPTION, on_revocacion)
    log.info("perfilamiento suscrito a %s (backend=%s)", TOPIC, bus.BACKEND)


@app.get("/perfil/{cliente}")
async def perfil(cliente: str):
    with _lock:
        if cliente in _revocados:
            return {"cliente": cliente, "origen_perfil": "sin_consentimiento"}
        if cliente in _cache_local:
            return {"cliente": cliente, "origen_perfil": "cache_local",
                    "score": _cache_local[cliente]}
    async with httpx.AsyncClient() as c:
        try:
            r = await c.get(OPEN_FINANCE_URL, params={"cliente": cliente}, timeout=2)
            r.raise_for_status()
            score = r.json()["score"]
        except Exception:
            return {"cliente": cliente, "origen_perfil": "defecto"}
    with _lock:
        _cache_local[cliente] = score
    return {"cliente": cliente, "origen_perfil": "proveedor", "score": score}


@app.get("/health")
async def health():
    return {"status": "ok", "en_cache": len(_cache_local),
            "revocados": len(_revocados), **metricas}
