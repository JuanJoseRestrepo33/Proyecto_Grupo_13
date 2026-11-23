"""Dependencias de las rutas del canal web.

La web nunca ve el token: viaja en la cookie `sesion` (`HttpOnly`, `Secure`,
`SameSite=Lax`), que el navegador manda sola porque la web y el BFF comparten origen.
"""

from __future__ import annotations

from typing import Annotated

from fastapi import Depends, Request

from app.comun.nucleo import ClienteNucleo, obtener_nucleo
from app.comun.sesion import ClienteAutenticado, autenticar

PREFIJO = "/api/v1/web"
CANAL = "web"
COOKIE_SESION = "sesion"


def cliente_web(request: Request) -> ClienteAutenticado:
    return autenticar(request, request.cookies.get(COOKIE_SESION), CANAL)


ClienteWeb = Annotated[ClienteAutenticado, Depends(cliente_web)]
Nucleo = Annotated[ClienteNucleo, Depends(obtener_nucleo)]
