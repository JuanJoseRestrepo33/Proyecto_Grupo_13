"""Almacén en memoria.

Sirve para desarrollo local sin base de datos y para las pruebas. Encadena igual que el
de PostgreSQL y asigna la secuencia bajo cerrojo, así que la cadena resiste escritores
concurrentes también aquí. Lo que no puede ofrecer es la protección en la base
(criterio 2): eso es exclusivo de `AlmacenPostgres`.
"""

from __future__ import annotations

import threading

from ..dominio import cadena
from ..dominio.modelo import EventoAuditable, Registro


class AlmacenEnMemoria:
    def __init__(self) -> None:
        self._registros: list[Registro] = []
        self._cerrojo = threading.Lock()

    def anexar(self, id_decision: str, evento: EventoAuditable) -> Registro:
        with self._cerrojo:
            anterior = self._registros[-1].hash if self._registros else cadena.HASH_GENESIS
            registro = cadena.encadenar(len(self._registros) + 1, anterior, id_decision, evento)
            self._registros.append(registro)
            return registro

    def obtener(self, id_decision: str) -> Registro | None:
        with self._cerrojo:
            return next((r for r in self._registros if r.id_decision == id_decision), None)

    def todos(self) -> tuple[Registro, ...]:
        with self._cerrojo:
            return tuple(self._registros)
