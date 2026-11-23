"""Repositorio de pagos en memoria, para desarrollo local y pruebas.

La reserva se decide bajo un solo cerrojo, igual que las restricciones `UNIQUE` del
repositorio en PostgreSQL: dos intentos simultáneos no pueden quedar los dos activos.
"""

from __future__ import annotations

import threading
from collections.abc import Sequence

from ..dominio.modelo import Pago


class RepositorioEnMemoria:
    def __init__(self) -> None:
        self._por_id: dict[str, Pago] = {}
        self._cerrojo = threading.Lock()

    def reservar(self, pago: Pago) -> Pago:
        with self._cerrojo:
            for existente in self._por_id.values():
                if existente.clave_idempotencia == pago.clave_idempotencia:
                    return existente
            for existente in self._por_id.values():
                if existente.concepto == pago.concepto and existente.activo:
                    return existente
            self._por_id[pago.id_pago] = pago
            return pago

    def actualizar(self, pago: Pago) -> None:
        with self._cerrojo:
            self._por_id[pago.id_pago] = pago

    def liberar(self, id_pago: str) -> None:
        with self._cerrojo:
            self._por_id.pop(id_pago, None)

    def obtener(self, id_pago: str) -> Pago | None:
        with self._cerrojo:
            return self._por_id.get(id_pago)

    def del_concepto(self, concepto: str) -> tuple[Pago, ...]:
        # Los dict conservan el orden de inserción: es el orden de llegada de los intentos.
        with self._cerrojo:
            return tuple(p for p in self._por_id.values() if p.concepto == concepto)

    def ultimos(self, conceptos: Sequence[str]) -> dict[str, Pago]:
        buscados = set(conceptos)
        with self._cerrojo:
            # El último en el orden de inserción pisa a los anteriores.
            return {p.concepto: p for p in self._por_id.values() if p.concepto in buscados}
