"""Puertos del módulo de consentimiento.

RepositorioConsentimientos → persistencia del propio módulo
RegistroDeAuditoria        → módulo de auditoría (ARQ-03), por su interfaz pública
BusDeEventos               → kernel compartido; perfilamiento se suscribe (criterio 5)
"""

from __future__ import annotations

from typing import Protocol

from .modelo import Consentimiento, EventoConsentimiento


class RepositorioConsentimientos(Protocol):
    def guardar(self, consentimiento: Consentimiento) -> None:
        raise NotImplementedError

    def obtener(self, id_consentimiento: str) -> Consentimiento | None:
        raise NotImplementedError

    def del_cliente(self, id_cliente: str) -> tuple[Consentimiento, ...]:
        """Todos los del cliente, del más antiguo al más reciente."""
        raise NotImplementedError


class RegistroDeAuditoria(Protocol):
    def registrar(self, consentimiento: Consentimiento, evento: EventoConsentimiento) -> str:
        """Deja el evento en el log de auditoría y devuelve el id de la entrada."""
        raise NotImplementedError
