"""Errores que el BFF devuelve a los canales.

Los dos contratos usan el mismo formato: `{codigo, mensaje}`, con campos extra cuando el
contrato los pide (`campos` en una validación, `intentos_restantes` en el OTP). El cliente
decide qué mostrar por el `codigo`; `mensaje` es para diagnóstico y nunca se muestra tal
cual. El núcleo responde `application/problem+json`: la traducción vive en `nucleo.py`.
"""

from __future__ import annotations

from collections.abc import Mapping

from fastapi import Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

#: 422 por número: Starlette renombró la constante entre versiones.
_SOLICITUD_INVALIDA = 422


class ErrorBff(Exception):
    """Un error con su estado HTTP y su código estable para el canal."""

    def __init__(
        self,
        estado: int,
        codigo: str,
        mensaje: str,
        *,
        detalles: Mapping[str, object] | None = None,
        encabezados: Mapping[str, str] | None = None,
        extra: Mapping[str, object] | None = None,
    ) -> None:
        super().__init__(mensaje)
        self.estado = estado
        self.codigo = codigo
        self.mensaje = mensaje
        #: Lo que el núcleo agregó al error; el router decide si algo llega al canal.
        self.detalles = dict(detalles or {})
        self.encabezados = dict(encabezados or {})
        #: Campos que sí van en la respuesta, junto a `codigo` y `mensaje`.
        self.extra = dict(extra or {})


async def manejar_error_bff(_: Request, error: Exception) -> JSONResponse:
    assert isinstance(error, ErrorBff)
    cuerpo = {"codigo": error.codigo, "mensaje": error.mensaje, **error.extra}
    return JSONResponse(status_code=error.estado, content=cuerpo, headers=error.encabezados)


#: Código general de una solicitud inválida según el primer campo que falla. Son los que la
#: web ya maneja desde sus mocks.
CODIGOS_POR_CAMPO = {"producto": "producto_invalido", "asegurado": "asegurado_invalido"}

_CODIGOS_POR_TIPO = {
    "missing": "obligatorio",
    "greater_than_equal": "fuera_de_rango",
    "less_than_equal": "fuera_de_rango",
    "greater_than": "fuera_de_rango",
    "less_than": "fuera_de_rango",
    "string_too_short": "longitud_invalida",
    "string_too_long": "longitud_invalida",
    "string_pattern_mismatch": "formato_invalido",
    "value_error": "formato_invalido",
    "enum": "valor_no_admitido",
    "literal_error": "valor_no_admitido",
    "extra_forbidden": "no_admitido",
}


def _campo(ubicacion: tuple[object, ...]) -> str:
    partes = [str(p) for p in ubicacion]
    if partes and partes[0] in ("body", "query", "path", "header"):
        partes = partes[1:]
    return ".".join(partes) or "cuerpo"


async def manejar_validacion(_: Request, error: Exception) -> JSONResponse:
    """422 con un error por campo, para la validación en línea de los formularios."""
    assert isinstance(error, RequestValidationError)
    campos = [
        {"campo": _campo(tuple(e["loc"])), "codigo": _CODIGOS_POR_TIPO.get(e["type"], "invalido")}
        for e in error.errors()
    ]
    primero = campos[0]["campo"].split(".")[0] if campos else ""
    cuerpo = {
        "codigo": CODIGOS_POR_CAMPO.get(primero, "solicitud_invalida"),
        "mensaje": "Los datos enviados no son válidos",
        "campos": campos,
    }
    return JSONResponse(status_code=_SOLICITUD_INVALIDA, content=cuerpo)
