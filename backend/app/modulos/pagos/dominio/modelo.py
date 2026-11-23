"""Modelo del cobro de una prima contra la pasarela (WEB-F03, criterio 2).

Un `Pago` es un intento de cobro identificado por la `Idempotency-Key` que genera el canal.
La misma clave es siempre el mismo intento: un doble clic o un reintento por red lenta nunca
cobra dos veces (P18). El pago se reserva en estado `procesando` **antes** de llamar a la
pasarela; mientras está así, repetir la clave responde `pago_en_curso`.

El `concepto` es lo que se cobra (hoy, una suscripción). Un concepto admite un solo pago
activo a la vez (`procesando`, `pendiente` o `aprobado`); tras un rechazo se intenta con una
clave nueva.
"""

from __future__ import annotations

from dataclasses import dataclass, replace
from datetime import datetime
from enum import StrEnum

from app.compartido.dinero import Dinero


class MedioPago(StrEnum):
    TARJETA_CREDITO = "tarjeta_credito"
    PSE = "pse"


class EstadoPago(StrEnum):
    #: Reservado; la pasarela todavía no responde. No sale del núcleo como estado final.
    PROCESANDO = "procesando"
    APROBADO = "aprobado"
    RECHAZADO = "rechazado"
    #: El banco aún no confirma (típico de PSE); se resuelve al consultar la pasarela.
    PENDIENTE = "pendiente"


#: Mientras un pago está en uno de estos estados, el concepto no admite otro intento.
ESTADOS_ACTIVOS = frozenset({EstadoPago.PROCESANDO, EstadoPago.PENDIENTE, EstadoPago.APROBADO})


@dataclass(frozen=True, slots=True)
class Pago:
    id_pago: str
    concepto: str
    id_cliente: str
    medio: MedioPago
    monto: Dinero
    clave_idempotencia: str
    canal: str
    creado_en: datetime
    estado: EstadoPago = EstadoPago.PROCESANDO
    #: Referencia de la transacción en la pasarela; no existe mientras está `procesando`.
    referencia: str | None = None
    actualizado_en: datetime | None = None
    #: Entrada del log de auditoría con el último resultado de la pasarela (RC-08).
    id_decision: str | None = None

    @property
    def activo(self) -> bool:
        return self.estado in ESTADOS_ACTIVOS

    def resuelto(self, estado: EstadoPago, referencia: str | None, instante: datetime) -> Pago:
        return replace(
            self,
            estado=estado,
            referencia=referencia or self.referencia,
            actualizado_en=instante,
        )


@dataclass(frozen=True, slots=True)
class RespuestaPasarela:
    estado: EstadoPago
    referencia: str
