"""Punto de entrada del BFF de Solventa."""

from __future__ import annotations

from fastapi import FastAPI

from app import salud
from app.comun.configuracion import Configuracion

VERSION_APP = "0.1.0"


def crear_app(configuracion: Configuracion | None = None) -> FastAPI:
    """Arma el BFF. `configuracion` existe para que las pruebas no dependan del entorno."""
    configuracion = configuracion or Configuracion.desde_entorno()
    app = FastAPI(
        title="Solventa · BFF",
        version=VERSION_APP,
        description=(
            "Backend for Frontend de los dos canales. `/api/v1/web/` cumple el contrato "
            "`contratos/bff-web` y `/api/v1/movil/` el contrato `contratos/bff-movil`. "
            "Orquesta el núcleo por HTTP; nunca importa su código."
        ),
    )
    app.state.configuracion = configuracion
    app.include_router(salud.construir_router(VERSION_APP))
    return app


app = crear_app()
