"""DTO del contrato v1 de pagos. Suscripción los reutiliza en sus rutas de checkout."""

from __future__ import annotations

from datetime import datetime

from pydantic import BaseModel, ConfigDict

from ..dominio.modelo import EstadoPago, MedioPago, Pago


class PagoDto(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    id: str
    concepto: str
    estado: EstadoPago
    medio: MedioPago
    monto: int
    moneda: str
    referencia: str | None
    creado_en: datetime
    actualizado_en: datetime | None
    #: `true` si la respuesta repite un intento ya procesado con la misma clave.
    reintento: bool = False
    id_decision: str | None = None


def a_pago_dto(pago: Pago, reintento: bool = False) -> PagoDto:
    return PagoDto(
        id=pago.id_pago,
        concepto=pago.concepto,
        estado=pago.estado,
        medio=pago.medio,
        monto=pago.monto.monto,
        moneda=pago.monto.moneda,
        referencia=pago.referencia,
        creado_en=pago.creado_en,
        actualizado_en=pago.actualizado_en,
        reintento=reintento,
        id_decision=pago.id_decision,
    )
