"""Reloj inyectable.

El dominio nunca llama a `datetime.now()` directamente: la vigencia de una oferta es
una regla de negocio y tiene que poder probarse sin esperar cinco minutos reales.
"""

from __future__ import annotations

from datetime import UTC, datetime
from typing import Protocol


class Reloj(Protocol):
    def ahora(self) -> datetime:
        raise NotImplementedError


class RelojDelSistema:
    """Reloj de producción. Siempre en UTC."""

    def ahora(self) -> datetime:
        return datetime.now(UTC)


class RelojFijo:
    """Reloj controlado, para pruebas."""

    def __init__(self, instante: datetime) -> None:
        self._instante = instante

    def ahora(self) -> datetime:
        return self._instante

    def avanzar(self, segundos: float) -> None:
        from datetime import timedelta

        self._instante += timedelta(seconds=segundos)
