"""Fachada versionada del núcleo.

Es la interfaz estable que consumen los BFF y los socios embebidos. Agrega los routers
de cada módulo bajo un único prefijo `/v1`, de modo que la reorganización interna del
monolito no cambie ninguna ruta pública (RC-05, RC-06).
"""

from __future__ import annotations

from fastapi import APIRouter

from app.modulos.cotizacion.api.rutas import router as router_cotizacion

VERSION = "v1"

router = APIRouter(prefix=f"/{VERSION}")
router.include_router(router_cotizacion)


@router.get("/", tags=["fachada"], summary="Índice de la fachada")
def indice() -> dict[str, object]:
    """Capacidades publicadas en esta versión.

    El despliegue continuo usa esta ruta como prueba de humo (`cd.yml`).
    """
    return {
        "version": VERSION,
        "estado": "ok",
        "capacidades": ["cotizacion"],
    }
