"""Punto de entrada del núcleo transaccional de Solventa."""

from __future__ import annotations

from collections.abc import Sequence

from fastapi import FastAPI

from app.compartido.errores import ErrorDeNegocio
from app.compartido.modulo import DescriptorDeModulo
from app.fachada import salud
from app.fachada.errores import manejar_error_de_negocio
from app.fachada.registro import descubrir_modulos
from app.fachada.v1 import router as v1

VERSION_APP = "0.1.0"


def crear_app(modulos: Sequence[DescriptorDeModulo] | None = None) -> FastAPI:
    """Arma la aplicación con los módulos descubiertos en `app.modulos`.

    `modulos` existe para que las pruebas armen un núcleo con descriptores propios.
    """
    if modulos is None:
        modulos = descubrir_modulos()
    app = FastAPI(
        title="Solventa · núcleo transaccional",
        version=VERSION_APP,
        description=(
            "Monolito modular con fachada versionada. Un módulo por capacidad de negocio; "
            "`/v1/` lista cuáles ya publican rutas y `/salud` reporta el estado de cada uno."
        ),
    )
    app.add_exception_handler(ErrorDeNegocio, manejar_error_de_negocio)
    app.include_router(v1.construir_router(modulos))
    app.include_router(salud.construir_router(modulos, VERSION_APP))
    for modulo in modulos:
        modulo.iniciar()
    return app


app = crear_app()
