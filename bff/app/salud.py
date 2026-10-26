"""Estado del BFF (`GET /salud`).

Cloud Run la usa como sonda de arranque: si responde 503, la revisión nueva no recibe
tráfico. Por eso solo responde 503 por lo que el BFF puede arreglar al reiniciar con otra
configuración, nunca porque el núcleo esté caído en ese momento: eso se reporta como
`degradado`, pero reiniciar el BFF no lo arregla.
"""

from __future__ import annotations

from fastapi import APIRouter, Request, Response, status

from app.comun.configuracion import Configuracion
from app.comun.nucleo import ClienteNucleo


def informe(configuracion: Configuracion, nucleo: ClienteNucleo, version: str) -> dict[str, object]:
    estado_nucleo = nucleo.estado()
    return {
        "estado": "ok" if estado_nucleo == "ok" else "degradado",
        "version": version,
        "nucleo": {"url": configuracion.nucleo_url, "estado": estado_nucleo},
    }


def construir_router(version: str) -> APIRouter:
    router = APIRouter(tags=["operación"])

    @router.get("/salud", summary="Estado del BFF y de lo que necesita para atender")
    def salud(request: Request, response: Response) -> dict[str, object]:
        cuerpo = informe(request.app.state.configuracion, request.app.state.nucleo, version)
        if cuerpo["estado"] == "caido":
            response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
        return cuerpo

    return router
