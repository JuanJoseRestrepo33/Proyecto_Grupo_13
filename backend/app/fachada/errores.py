"""Traducción de errores de negocio a respuestas HTTP.

El formato es `application/problem+json` (RFC 9457): un contrato de error estable y
versionado, igual que el de los datos. Los módulos lanzan excepciones del dominio y
nunca deciden códigos HTTP.
"""

from __future__ import annotations

from fastapi import Request, status
from fastapi.responses import JSONResponse

from app.compartido.errores import (
    DatoInvalido,
    DemasiadasSolicitudes,
    DependenciaNoDisponible,
    ErrorDeNegocio,
    NoAutenticado,
    RecursoNoEncontrado,
    ReglaDeNegocioViolada,
)

ESTADOS: dict[type[ErrorDeNegocio], int] = {
    NoAutenticado: status.HTTP_401_UNAUTHORIZED,
    RecursoNoEncontrado: status.HTTP_404_NOT_FOUND,
    ReglaDeNegocioViolada: status.HTTP_409_CONFLICT,
    # 422 y 429 por número: Starlette renombró la constante del 422 entre versiones.
    DatoInvalido: 422,
    DemasiadasSolicitudes: 429,
    DependenciaNoDisponible: status.HTTP_503_SERVICE_UNAVAILABLE,
}


def _estado(error: ErrorDeNegocio) -> int:
    for tipo, codigo in ESTADOS.items():
        if isinstance(error, tipo):
            return codigo
    return status.HTTP_400_BAD_REQUEST


async def manejar_error_de_negocio(request: Request, error: Exception) -> JSONResponse:
    assert isinstance(error, ErrorDeNegocio)
    estado = _estado(error)
    cuerpo = {
        "type": f"https://solventa.example/errores/{error.codigo}",
        "title": error.codigo,
        "status": estado,
        "detail": error.mensaje,
        "instance": str(request.url.path),
    }
    if error.detalles:
        cuerpo["detalles"] = error.detalles
    encabezados = {}
    if isinstance(error, DemasiadasSolicitudes) and "reintentar_en_segundos" in error.detalles:
        encabezados["Retry-After"] = str(error.detalles["reintentar_en_segundos"])
    return JSONResponse(
        status_code=estado,
        content=cuerpo,
        media_type="application/problem+json",
        headers=encabezados,
    )
