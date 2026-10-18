"""Bus de eventos en proceso.

Los módulos no se llaman entre sí para avisar que algo pasó: publican un evento y quien
necesite enterarse se suscribe. Es lo que permite que una revocación de consentimiento
invalide la caché de perfilamiento sin que consentimiento sepa que esa caché existe
(WEB-F02, criterio 5).

Hoy el bus vive en el proceso del monolito. Cuando el canal de eventos de EXP-02 llegue
al núcleo, esta interfaz se conserva y cambia la implementación.
"""

from __future__ import annotations

import logging
import threading
from collections import deque
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from datetime import datetime
from typing import Protocol

from .unica import instancia_unica

log = logging.getLogger(__name__)


@dataclass(frozen=True)
class Evento:
    nombre: str
    carga: Mapping[str, object]
    ocurrido_en: datetime


Manejador = Callable[[Evento], None]


class BusDeEventos(Protocol):
    def publicar(self, evento: Evento) -> int:
        """Entrega el evento a sus suscriptores y devuelve a cuántos llegó."""
        raise NotImplementedError

    def suscribir(self, nombre: str, manejador: Manejador) -> None:
        raise NotImplementedError


class BusEnProceso:
    """Entrega síncrona y en orden de suscripción.

    Un manejador que falla no detiene a los demás: se registra en el log y se sigue. El
    publicador no debe depender de que todos los suscriptores funcionen.
    """

    def __init__(self, memoria: int = 1000) -> None:
        self._suscriptores: dict[str, list[Manejador]] = {}
        self._cerrojo = threading.Lock()
        self._historial: deque[Evento] = deque(maxlen=memoria)

    def suscribir(self, nombre: str, manejador: Manejador) -> None:
        with self._cerrojo:
            manejadores = self._suscriptores.setdefault(nombre, [])
            if manejador not in manejadores:
                manejadores.append(manejador)

    def publicar(self, evento: Evento) -> int:
        with self._cerrojo:
            manejadores = list(self._suscriptores.get(evento.nombre, ()))
            self._historial.append(evento)
        entregados = 0
        for manejador in manejadores:
            try:
                manejador(evento)
                entregados += 1
            except Exception:
                log.exception("Falló un suscriptor de %s", evento.nombre)
        return entregados

    def publicados(self, nombre: str | None = None) -> tuple[Evento, ...]:
        with self._cerrojo:
            return tuple(e for e in self._historial if nombre is None or e.nombre == nombre)

    def suscriptores(self, nombre: str) -> int:
        with self._cerrojo:
            return len(self._suscriptores.get(nombre, ()))


@instancia_unica
def obtener_bus() -> BusEnProceso:
    """El bus del proceso. Los módulos lo obtienen en su composición, no lo crean."""
    return BusEnProceso()
