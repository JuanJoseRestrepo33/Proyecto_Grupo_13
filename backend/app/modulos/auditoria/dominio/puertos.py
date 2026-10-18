"""Puertos del módulo de auditoría."""

from __future__ import annotations

from typing import Protocol

from .modelo import EventoAuditable, Registro


class AlmacenDeRegistros(Protocol):
    """Almacén append-only.

    Solo sabe anexar y leer. No existe operación de modificar ni de borrar, ni aquí ni
    en ninguna implementación: la de PostgreSQL además lo impide en la propia base.
    `anexar` debe asignar secuencia y hash anterior de forma atómica frente a otros
    escritores (criterio 5).
    """

    def anexar(self, id_decision: str, evento: EventoAuditable) -> Registro:
        raise NotImplementedError

    def obtener(self, id_decision: str) -> Registro | None:
        raise NotImplementedError

    def todos(self) -> tuple[Registro, ...]:
        """Todos los registros, en orden de secuencia."""
        raise NotImplementedError
