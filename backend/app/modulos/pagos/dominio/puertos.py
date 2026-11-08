"""Puertos del módulo de pagos.

RepositorioPagos    → persistencia del propio módulo
PasarelaDePagos     → proveedor externo de cobro (simulado en este prototipo)
RegistroDeAuditoria → log de auditoría (ARQ-03), por su interfaz pública
"""

from __future__ import annotations

from typing import Protocol

from app.compartido.dinero import Dinero

from .modelo import MedioPago, Pago, RespuestaPasarela


class RepositorioPagos(Protocol):
    def reservar(self, pago: Pago) -> Pago:
        """Guarda el pago en `procesando` si se puede, de forma atómica.

        - Si ya hay un pago con la misma clave, no guarda nada y devuelve ese.
        - Si el concepto ya tiene un pago activo, no guarda nada y devuelve el activo.
        - Si no, guarda el nuevo y lo devuelve.

        Quien llama distingue los tres casos comparando `id_pago` y la clave.
        """
        raise NotImplementedError

    def actualizar(self, pago: Pago) -> None:
        raise NotImplementedError

    def liberar(self, id_pago: str) -> None:
        """Borra una reserva que nunca llegó a la pasarela (la pasarela no respondió)."""
        raise NotImplementedError

    def obtener(self, id_pago: str) -> Pago | None:
        raise NotImplementedError

    def del_concepto(self, concepto: str) -> tuple[Pago, ...]:
        """Los pagos del concepto, del más antiguo al más reciente."""
        raise NotImplementedError


class PasarelaDePagos(Protocol):
    def cobrar(self, id_pago: str, monto: Dinero, medio: MedioPago) -> RespuestaPasarela:
        """Cobra el monto. Lanza `DependenciaNoDisponible` si la pasarela no responde: en ese
        caso no hubo cobro."""
        raise NotImplementedError

    def consultar(self, referencia: str) -> RespuestaPasarela:
        """El estado actual de una transacción, para resolver un pago pendiente."""
        raise NotImplementedError


class RegistroDeAuditoria(Protocol):
    def registrar(self, pago: Pago) -> str:
        """Deja el resultado de la pasarela en el log y devuelve el id de la entrada."""
        raise NotImplementedError
