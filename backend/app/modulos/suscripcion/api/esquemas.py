"""DTO del contrato v1 de suscripción. El BFF los traduce al contrato del canal.

Dentro de `v1` solo se agregan campos opcionales.
"""

from __future__ import annotations

from datetime import date, datetime
from enum import StrEnum

from pydantic import BaseModel, ConfigDict, Field

from ..dominio.modelo import EstadoSuscripcion, PagoDeLaPrima, Paso, Suscripcion


class Canal(StrEnum):
    WEB = "web"
    MOVIL = "movil"


class MedioPago(StrEnum):
    TARJETA_CREDITO = "tarjeta_credito"
    PSE = "pse"


class _Dto(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


class SolicitudSuscripcionDto(_Dto):
    id_cotizacion: str = Field(min_length=1, max_length=128)


class SolicitudPagoDto(_Dto):
    medio: MedioPago


class SolicitudFirmaDto(_Dto):
    acepta_condiciones: bool = Field(
        description="El cliente acepta expresamente las condiciones que firma"
    )


class CoberturaDto(_Dto):
    nombre: str
    limite: str


class PagoDto(_Dto):
    id: str
    estado: str = Field(description="aprobado, rechazado o pendiente")
    medio: str
    monto: int
    moneda: str
    referencia: str | None
    creado_en: datetime
    actualizado_en: datetime | None
    reintento: bool = Field(
        default=False, description="true si repite un intento ya procesado con la misma clave"
    )


class PolizaEmitidaDto(_Dto):
    numero: str
    inicio: date
    fin: date


class PasoAuditadoDto(_Dto):
    paso: Paso
    id_decision: str = Field(description="Entrada del log: /v1/auditoria/registros/{id}")
    ocurrido_en: datetime


class SuscripcionDto(_Dto):
    id: str
    id_cotizacion: str
    id_cliente: str
    producto: str
    nombre_producto: str
    prima_mensual: int
    moneda: str
    valor_asegurado: str
    deducible: str
    coberturas: tuple[CoberturaDto, ...]
    estado: EstadoSuscripcion
    canal: str
    aceptada_en: datetime
    pagada_en: datetime | None
    firmada_en: datetime | None
    id_firma: str | None
    pago: PagoDto | None = Field(description="Último intento de pago; null si no hay")
    poliza: PolizaEmitidaDto | None = Field(description="La póliza emitida; null hasta firmar")
    trazabilidad: tuple[PasoAuditadoDto, ...]


def a_pago_dto(pago: PagoDeLaPrima, reintento: bool = False) -> PagoDto:
    return PagoDto(
        id=pago.id_pago,
        estado=pago.estado,
        medio=pago.medio,
        monto=pago.monto.monto,
        moneda=pago.monto.moneda,
        referencia=pago.referencia,
        creado_en=pago.creado_en,
        actualizado_en=pago.actualizado_en,
        reintento=reintento,
    )


def a_suscripcion_dto(suscripcion: Suscripcion, pago: PagoDeLaPrima | None) -> SuscripcionDto:
    condiciones = suscripcion.condiciones
    poliza = None
    if suscripcion.numero_poliza is not None:
        poliza = PolizaEmitidaDto(
            numero=suscripcion.numero_poliza,
            inicio=suscripcion.poliza_inicio,  # type: ignore[arg-type]
            fin=suscripcion.poliza_fin,  # type: ignore[arg-type]
        )
    return SuscripcionDto(
        id=suscripcion.id_suscripcion,
        id_cotizacion=suscripcion.id_cotizacion,
        id_cliente=suscripcion.id_cliente,
        producto=condiciones.producto,
        nombre_producto=condiciones.nombre_producto,
        prima_mensual=condiciones.prima_mensual.monto,
        moneda=condiciones.prima_mensual.moneda,
        valor_asegurado=condiciones.valor_asegurado,
        deducible=condiciones.deducible,
        coberturas=tuple(
            CoberturaDto(nombre=c.nombre, limite=c.limite) for c in condiciones.coberturas
        ),
        estado=suscripcion.estado,
        canal=suscripcion.canal,
        aceptada_en=suscripcion.aceptada_en,
        pagada_en=suscripcion.pagada_en,
        firmada_en=suscripcion.firmada_en,
        id_firma=suscripcion.id_firma,
        pago=None if pago is None else a_pago_dto(pago),
        poliza=poliza,
        trazabilidad=tuple(
            PasoAuditadoDto(paso=p.paso, id_decision=p.id_decision, ocurrido_en=p.ocurrido_en)
            for p in suscripcion.trazabilidad
        ),
    )
