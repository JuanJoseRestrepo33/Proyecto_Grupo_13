"""Dependencias de las rutas del canal móvil.

La app manda el token de acceso en `Authorization: Bearer`. Un 401 lleva
`WWW-Authenticate: Bearer`, como pide RFC 6750, para que la app sepa que debe renovar.
"""

from __future__ import annotations

from typing import Annotated

from fastapi import Depends, Request

from app.comun.nucleo import ClienteNucleo, obtener_nucleo
from app.comun.sesion import ClienteAutenticado, autenticar

PREFIJO = "/api/v1/movil"
CANAL = "movil"
_PORTADOR = "bearer "
DESAFIO = {"WWW-Authenticate": "Bearer"}


def _token_portador(request: Request) -> str | None:
    cabecera = request.headers.get("Authorization", "")
    if not cabecera.lower().startswith(_PORTADOR):
        return None
    return cabecera[len(_PORTADOR) :].strip() or None


def cliente_movil(request: Request) -> ClienteAutenticado:
    return autenticar(request, _token_portador(request), CANAL, encabezados_401=DESAFIO)


ClienteMovil = Annotated[ClienteAutenticado, Depends(cliente_movil)]
Nucleo = Annotated[ClienteNucleo, Depends(obtener_nucleo)]
