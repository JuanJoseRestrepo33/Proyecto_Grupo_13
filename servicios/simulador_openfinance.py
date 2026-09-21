"""
Simulador configurable del proveedor de Finanzas Abiertas (EXP-01).
Reemplaza a WireMock: permite fijar una tasa de error EXACTA y cambiar de
escenario en caliente, sin reiniciar nada.

  POST /admin/escenario  {"nombre": "b_degradado"}
  POST /admin/escenario  {"latencia_ms": 800, "error_rate": 0.15}
"""
import asyncio
import random
from fastapi import FastAPI, HTTPException, Request

app = FastAPI(title="Simulador Finanzas Abiertas")

ESCENARIOS = {
    "a_sano":      {"latencia_ms": 40,  "error_rate": 0.0},
    "b_degradado": {"latencia_ms": 800, "error_rate": 0.15},
    "c_caido":     {"latencia_ms": 900, "error_rate": 1.0},
}
estado = dict(ESCENARIOS["a_sano"], nombre="a_sano")
contadores = {"llamadas": 0, "errores": 0}


@app.post("/admin/escenario")
async def cambiar(req: Request):
    body = await req.json()
    if "nombre" in body:
        if body["nombre"] not in ESCENARIOS:
            raise HTTPException(400, f"escenarios validos: {list(ESCENARIOS)}")
        estado.update(ESCENARIOS[body["nombre"]], nombre=body["nombre"])
    else:
        estado.update(latencia_ms=body["latencia_ms"],
                      error_rate=body["error_rate"], nombre="personalizado")
    contadores.update(llamadas=0, errores=0)
    return estado


@app.get("/admin/estado")
async def ver():
    return {**estado, **contadores}


@app.get("/perfil")
async def perfil(cliente: str):
    contadores["llamadas"] += 1
    lat = max(1.0, random.gauss(estado["latencia_ms"], estado["latencia_ms"] * 0.15))
    await asyncio.sleep(lat / 1000)
    if random.random() < estado["error_rate"]:
        contadores["errores"] += 1
        raise HTTPException(503, "proveedor no disponible")
    return {"cliente": cliente, "score": random.randint(550, 820), "fuente": "open-finance"}


@app.get("/health")
async def health():
    return {"status": "ok"}
