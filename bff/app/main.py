"""Punto de entrada del BFF de Solventa."""

from __future__ import annotations

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

import httpx
from fastapi import FastAPI
from fastapi.exceptions import RequestValidationError

from app import salud
from app.comun.configuracion import Configuracion
from app.comun.errores import ErrorBff, manejar_error_bff, manejar_validacion
from app.comun.nucleo import ClienteNucleo
from app.comun.tokens import LlaveInvalida, VerificadorDeTokens

VERSION_APP = "0.1.0"


def _verificador(configuracion: Configuracion) -> tuple[VerificadorDeTokens, str | None]:
    """Una llave mal configurada no tumba el arranque: `/salud` lo reporta y responde 503."""
    try:
        return VerificadorDeTokens.desde_pem(configuracion.llaves_publicas), None
    except LlaveInvalida as error:
        return VerificadorDeTokens(), str(error)


def crear_app(
    configuracion: Configuracion | None = None,
    transporte_nucleo: httpx.BaseTransport | None = None,
) -> FastAPI:
    """Arma el BFF.

    `configuracion` y `transporte_nucleo` existen para que las pruebas no dependan del
    entorno ni de un núcleo corriendo: el transporte reemplaza la red por un núcleo falso.
    """
    configuracion = configuracion or Configuracion.desde_entorno()
    nucleo = ClienteNucleo(
        configuracion.nucleo_url, configuracion.tiempo_espera_nucleo, transporte_nucleo
    )

    @asynccontextmanager
    async def ciclo_de_vida(_: FastAPI) -> AsyncIterator[None]:
        yield
        nucleo.cerrar()

    app = FastAPI(
        title="Solventa · BFF",
        version=VERSION_APP,
        description=(
            "Backend for Frontend de los dos canales. `/api/v1/web/` cumple el contrato "
            "`contratos/bff-web` y `/api/v1/movil/` el contrato `contratos/bff-movil`. "
            "Orquesta el núcleo por HTTP; nunca importa su código."
        ),
        lifespan=ciclo_de_vida,
    )
    app.state.configuracion = configuracion
    app.state.nucleo = nucleo
    app.state.verificador, app.state.error_llaves = _verificador(configuracion)
    app.add_exception_handler(ErrorBff, manejar_error_bff)
    app.add_exception_handler(RequestValidationError, manejar_validacion)
    app.include_router(salud.construir_router(VERSION_APP))
    return app


app = crear_app()
