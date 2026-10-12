"""Punto de entrada del núcleo transaccional de Solventa."""

from __future__ import annotations

from fastapi import FastAPI

from app.compartido.errores import ErrorDeNegocio
from app.fachada.errores import manejar_error_de_negocio
from app.fachada.v1.router import router as router_v1


def crear_app() -> FastAPI:
    app = FastAPI(
        title="Solventa · núcleo transaccional",
        version="0.1.0",
        description=(
            "Monolito modular con fachada versionada. Módulos publicados: cotización (WEB-F01)."
        ),
    )
    app.add_exception_handler(ErrorDeNegocio, manejar_error_de_negocio)
    app.include_router(router_v1)

    @app.get("/salud", tags=["operación"], summary="Estado del servicio")
    def salud() -> dict[str, str]:
        return {"estado": "ok"}

    return app


app = crear_app()
