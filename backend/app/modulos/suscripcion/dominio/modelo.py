"""Modelo de la suscripción: la oferta aceptada camino a ser póliza (WEB-F03).

    aceptada ──pago aprobado──▶ pagada ──firma──▶ emitida

La suscripción nace al aceptar una cotización vigente y **copia sus condiciones**: lo que el
cliente aceptó no cambia aunque la cotización o el catálogo cambien después (RC-08). Una
cotización se acepta una sola vez; aceptarla de nuevo devuelve la misma suscripción.

Los vínculos con otros módulos son por identificador: la cotización, el pago (módulo pagos)
y la póliza (módulo pólizas). Cada paso deja su entrada en el log de auditoría y la
suscripción guarda la referencia, para que el criterio 5 se verifique desde la propia
suscripción.
"""

from __future__ import annotations

from dataclasses import dataclass, replace
from datetime import date, datetime
from enum import StrEnum

from app.compartido.dinero import Dinero


class EstadoSuscripcion(StrEnum):
    ACEPTADA = "aceptada"
    PAGADA = "pagada"
    EMITIDA = "emitida"


class Paso(StrEnum):
    """Los pasos que el criterio 5 exige encontrar en el log de auditoría."""

    ACEPTACION = "aceptacion"
    PAGO = "pago"
    FIRMA = "firma"
    EMISION = "emision"


@dataclass(frozen=True, slots=True)
class CoberturaOfertada:
    nombre: str
    limite: str


@dataclass(frozen=True, slots=True)
class CondicionesDeOferta:
    """Copia de la oferta aceptada."""

    id_cotizacion: str
    producto: str
    nombre_producto: str
    prima_mensual: Dinero
    valor_asegurado: str
    deducible: str
    coberturas: tuple[CoberturaOfertada, ...]


@dataclass(frozen=True, slots=True)
class PasoAuditado:
    paso: Paso
    id_decision: str
    ocurrido_en: datetime


@dataclass(frozen=True, slots=True)
class Firma:
    id_firma: str
    proveedor: str
    firmada_en: datetime


@dataclass(frozen=True, slots=True)
class PolizaEmitida:
    numero: str
    inicio: date
    fin: date
    id_decision: str | None


@dataclass(frozen=True, slots=True)
class PagoDeLaPrima:
    """Lo que suscripción necesita saber de un pago del módulo de pagos."""

    id_pago: str
    estado: str
    medio: str
    monto: Dinero
    referencia: str | None
    creado_en: datetime
    actualizado_en: datetime | None
    id_decision: str | None

    @property
    def aprobado(self) -> bool:
        return self.estado == "aprobado"

    @property
    def pendiente(self) -> bool:
        return self.estado == "pendiente"


@dataclass(frozen=True, slots=True)
class Suscripcion:
    id_suscripcion: str
    id_cliente: str
    condiciones: CondicionesDeOferta
    canal: str
    aceptada_en: datetime
    estado: EstadoSuscripcion = EstadoSuscripcion.ACEPTADA
    id_pago: str | None = None
    pagada_en: datetime | None = None
    id_firma: str | None = None
    firmada_en: datetime | None = None
    numero_poliza: str | None = None
    poliza_inicio: date | None = None
    poliza_fin: date | None = None
    emitida_en: datetime | None = None
    trazabilidad: tuple[PasoAuditado, ...] = ()

    @property
    def id_cotizacion(self) -> str:
        return self.condiciones.id_cotizacion

    def con_paso(self, paso: Paso, id_decision: str | None, instante: datetime) -> Suscripcion:
        if id_decision is None or any(p.paso is paso for p in self.trazabilidad):
            return self
        return replace(
            self, trazabilidad=(*self.trazabilidad, PasoAuditado(paso, id_decision, instante))
        )

    def pagada(self, pago: PagoDeLaPrima, instante: datetime) -> Suscripcion:
        if self.estado is not EstadoSuscripcion.ACEPTADA:
            return self
        return replace(
            self, estado=EstadoSuscripcion.PAGADA, id_pago=pago.id_pago, pagada_en=instante
        ).con_paso(Paso.PAGO, pago.id_decision, instante)

    def emitida(
        self, firma: Firma, id_decision_firma: str, poliza: PolizaEmitida, instante: datetime
    ) -> Suscripcion:
        return (
            replace(
                self,
                estado=EstadoSuscripcion.EMITIDA,
                id_firma=firma.id_firma,
                firmada_en=firma.firmada_en,
                numero_poliza=poliza.numero,
                poliza_inicio=poliza.inicio,
                poliza_fin=poliza.fin,
                emitida_en=instante,
            )
            .con_paso(Paso.FIRMA, id_decision_firma, firma.firmada_en)
            .con_paso(Paso.EMISION, poliza.id_decision, instante)
        )
