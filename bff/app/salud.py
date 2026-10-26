"""Estado del BFF (`GET /salud`).

Cloud Run la usa como sonda de arranque: si responde 503, la revisión nueva no recibe
tráfico. Por eso solo responde 503 por lo que el BFF puede arreglar al reiniciar con otra
configuración, como una llave de tokens que falta o no sirve. Que el núcleo esté caído se
reporta como `degradado`, pero no da 503: reiniciar el BFF no lo arregla.
"""

from __future__ import annotations

from fastapi import APIRouter, Request, Response, status

from app.comun.configuracion import Configuracion
from app.comun.nucleo import ClienteNucleo
from app.comun.tokens import VerificadorDeTokens


def _tokens(
    configuracion: Configuracion, verificador: VerificadorDeTokens, error: str | None
) -> dict[str, object]:
    if error:
        return {"estado": "caido", "detalle": error}
    if verificador.configurado:
        return {"estado": "ok", "kids": list(verificador.kids)}
    # En local se puede arrancar sin llave para probar lo que no exige sesión; en la nube
    # un BFF que no puede validar ninguna sesión no debe recibir tráfico.
    return {
        "estado": "caido" if configuracion.en_la_nube else "degradado",
        "detalle": "Falta TOKENS_LLAVE_PUBLICA",
    }


def informe(
    configuracion: Configuracion,
    nucleo: ClienteNucleo,
    verificador: VerificadorDeTokens,
    error_llaves: str | None,
    version: str,
) -> dict[str, object]:
    tokens = _tokens(configuracion, verificador, error_llaves)
    estado_nucleo = nucleo.estado()
    if tokens["estado"] == "caido":
        estado = "caido"
    elif tokens["estado"] == "ok" and estado_nucleo == "ok":
        estado = "ok"
    else:
        estado = "degradado"
    return {
        "estado": estado,
        "version": version,
        "nucleo": {"url": configuracion.nucleo_url, "estado": estado_nucleo},
        "tokens": tokens,
    }


def construir_router(version: str) -> APIRouter:
    router = APIRouter(tags=["operación"])

    @router.get("/salud", summary="Estado del BFF y de lo que necesita para atender")
    def salud(request: Request, response: Response) -> dict[str, object]:
        estado = request.app.state
        cuerpo = informe(
            estado.configuracion, estado.nucleo, estado.verificador, estado.error_llaves, version
        )
        if cuerpo["estado"] == "caido":
            response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
        return cuerpo

    return router
