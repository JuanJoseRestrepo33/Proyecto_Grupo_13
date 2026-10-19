"""Contrato que todo módulo de negocio cumple ante la fachada (ARQ-01).

La fachada no conoce a ningún módulo por nombre: recorre los paquetes de `app.modulos` y
de cada uno toma su descriptor `MODULO`. Por eso agregar una capacidad nueva no toca
ningún archivo existente, y por eso este contrato vive en el kernel y no en la fachada:
los módulos lo importan, pero el kernel no importa a nadie.
"""

from __future__ import annotations

from collections.abc import Callable, Sequence
from dataclasses import dataclass
from typing import Literal, Protocol

Estado = Literal["ok", "simulado", "degradado", "caido"]


@dataclass(frozen=True)
class EstadoDeDependencia:
    """Estado de una dependencia externa vista desde un módulo.

    `simulado` es un estado propio de este prototipo: la dependencia responde, pero con
    un adaptador simulado y no con el proveedor real. Se reporta aparte de `ok` para que
    nadie confunda un ambiente de pruebas con uno conectado.
    """

    nombre: str
    estado: Estado
    detalle: str = ""


class Enrutador(Protocol):
    """Lo mínimo que la fachada necesita de un router, sin que el kernel importe FastAPI."""

    @property
    def routes(self) -> Sequence[object]: ...


def sin_dependencias() -> Sequence[EstadoDeDependencia]:
    return ()


def sin_arranque() -> None:
    return None


@dataclass(frozen=True)
class DescriptorDeModulo:
    """Lo único que un módulo publica hacia la fachada.

    `nombre` debe coincidir con el nombre del paquete bajo `app.modulos`. `historia` es la
    historia de usuario que le da origen, para que el índice de la fachada y `/salud`
    digan de dónde sale cada capacidad. `iniciar` se invoca una vez al armar la aplicación:
    es donde un módulo se suscribe a los eventos que le interesan.
    """

    nombre: str
    descripcion: str
    historia: str
    router: Enrutador
    dependencias: Callable[[], Sequence[EstadoDeDependencia]] = sin_dependencias
    iniciar: Callable[[], None] = sin_arranque

    @property
    def publica_rutas(self) -> bool:
        return len(self.router.routes) > 0
