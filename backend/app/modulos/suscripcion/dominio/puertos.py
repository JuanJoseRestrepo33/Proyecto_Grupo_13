"""Puertos del módulo de suscripción.

ConsultaDeOfertas         → módulo cotización, por su interfaz pública (ARQ-01)
CobroDePrimas             → módulo pagos, por su interfaz pública
EmisionDePolizas          → módulo pólizas, por su interfaz pública
FirmaElectronica          → proveedor de firma (simulado en este prototipo)
RepositorioSuscripciones  → persistencia del propio módulo
RegistroDeAuditoria       → log de auditoría (ARQ-03)
"""

from __future__ import annotations

from collections.abc import Sequence
from datetime import datetime
from typing import Protocol

from .modelo import CondicionesDeOferta, Firma, PagoDeLaPrima, PolizaEmitida, Suscripcion


class ConsultaDeOfertas(Protocol):
    def condiciones(
        self, id_cotizacion: str, id_cliente: str, ahora: datetime
    ) -> CondicionesDeOferta:
        """Las condiciones de la oferta si la cotización es del cliente y sigue vigente.

        `CotizacionNoEncontradaError` si no existe o es de otro; `CotizacionVencidaError` si
        ya venció.
        """
        raise NotImplementedError


class CobroDePrimas(Protocol):
    def cobrar(
        self, suscripcion: Suscripcion, medio: str, clave_idempotencia: str, canal: str
    ) -> tuple[PagoDeLaPrima, bool]:
        """Cobra la prima. Devuelve el pago y si es un intento nuevo (no un reintento)."""
        raise NotImplementedError

    def consultar(self, suscripcion: Suscripcion, id_pago: str) -> PagoDeLaPrima:
        raise NotImplementedError

    def ultimo(self, suscripcion: Suscripcion) -> PagoDeLaPrima | None:
        raise NotImplementedError

    def ultimos(self, suscripciones: Sequence[Suscripcion]) -> dict[str, PagoDeLaPrima]:
        """El último pago de cada suscripción, por id, en una sola consulta."""
        raise NotImplementedError


class EmisionDePolizas(Protocol):
    def emitir(self, suscripcion: Suscripcion) -> PolizaEmitida:
        """Emite la póliza de una suscripción pagada y firmada. Es idempotente por
        suscripción."""
        raise NotImplementedError


class FirmaElectronica(Protocol):
    def firmar(self, suscripcion: Suscripcion) -> Firma:
        raise NotImplementedError


class RepositorioSuscripciones(Protocol):
    def guardar_si_nueva(self, suscripcion: Suscripcion) -> Suscripcion:
        """Guarda la suscripción, salvo que la cotización ya tenga una: entonces devuelve esa
        sin guardar nada. Es atómico."""
        raise NotImplementedError

    def actualizar(self, suscripcion: Suscripcion) -> Suscripcion | None:
        """Guarda los cambios si la versión guardada sigue siendo `suscripcion.version`, y
        devuelve la suscripción con su versión nueva. None si otra réplica escribió antes:
        quien llama relee y decide de nuevo."""
        raise NotImplementedError

    def obtener(self, id_suscripcion: str) -> Suscripcion | None:
        raise NotImplementedError

    def por_cotizacion(self, id_cotizacion: str) -> Suscripcion | None:
        raise NotImplementedError

    def del_cliente(self, id_cliente: str) -> tuple[Suscripcion, ...]:
        """De la más reciente a la más antigua."""
        raise NotImplementedError


class RegistroDeAuditoria(Protocol):
    def registrar_aceptacion(self, suscripcion: Suscripcion) -> str:
        raise NotImplementedError

    def registrar_firma(self, suscripcion: Suscripcion, firma: Firma) -> str:
        raise NotImplementedError
