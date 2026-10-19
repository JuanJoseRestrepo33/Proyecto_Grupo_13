"""Fachada versionada del núcleo.

Es la interfaz estable que consumen los BFF y los socios embebidos. Agrega los routers
de cada módulo bajo un único prefijo `/v1`, de modo que la reorganización interna del
monolito no cambie ninguna ruta pública (RC-05, RC-06).

Un cambio incompatible en el contrato no se hace aquí: se publica `app/fachada/v2/` y
`/v1` sigue sirviendo hasta que el último consumidor migre.
"""

from __future__ import annotations

from collections.abc import Sequence

from fastapi import APIRouter

from app.compartido.modulo import DescriptorDeModulo

VERSION = "v1"


def construir_router(modulos: Sequence[DescriptorDeModulo]) -> APIRouter:
    router = APIRouter(prefix=f"/{VERSION}")
    for modulo in modulos:
        router.include_router(modulo.router)

    capacidades = [m.nombre for m in modulos if m.publica_rutas]
    catalogo = {m.nombre: {"historia": m.historia, "descripcion": m.descripcion} for m in modulos}

    @router.get("/", tags=["fachada"], summary="Índice de la fachada")
    def indice() -> dict[str, object]:
        """Capacidades publicadas en esta versión.

        `capacidades` son los módulos que ya exponen rutas; `modulos` es el mapa completo
        del monolito, incluidos los esqueletos. El despliegue continuo usa esta ruta como
        prueba de humo (`cd.yml`).
        """
        return {
            "version": VERSION,
            "estado": "ok",
            "capacidades": capacidades,
            "modulos": catalogo,
        }

    return router
