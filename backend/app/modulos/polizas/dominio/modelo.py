"""Modelo de la póliza.

Una póliza emitida (WEB-F03) trae las condiciones de la oferta aceptada: coberturas con sus
límites, deducible, valor asegurado y prima. Las condiciones se **copian** de la oferta, no se
referencian: la póliza tiene que decir lo mismo años después, aunque el catálogo cambie
(RC-08). Las pólizas de prueba de *staging* (semilla) solo traen producto y vigencia.

La vigencia va por días calendario, con los dos extremos incluidos: una póliza que cubre del
1 al 31 cubre un evento ocurrido el 31.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime, timedelta
from enum import StrEnum

from app.compartido.dinero import Dinero


class EstadoPoliza(StrEnum):
    VIGENTE = "vigente"
    VENCIDA = "vencida"
    POR_INICIAR = "por_iniciar"


@dataclass(frozen=True, slots=True)
class CoberturaPoliza:
    nombre: str
    limite: str


@dataclass(frozen=True, slots=True)
class Poliza:
    numero: str
    id_cliente: str
    producto: str
    inicio: date
    fin: date
    # -- condiciones de la oferta aceptada; vacías en una póliza de semilla ----------------
    nombre_producto: str | None = None
    coberturas: tuple[CoberturaPoliza, ...] = ()
    deducible: str | None = None
    valor_asegurado: str | None = None
    prima_mensual: Dinero | None = None
    # -- trazabilidad de la emisión ----------------------------------------------------
    id_suscripcion: str | None = None
    id_firma: str | None = None
    emitida_en: datetime | None = None
    canal: str | None = None
    #: Documento de la póliza en el almacén; None si no tiene (semilla).
    id_documento: str | None = None
    #: Entrada del log de auditoría de la emisión (WEB-F03, criterio 5).
    id_decision: str | None = None

    def __post_init__(self) -> None:
        if self.fin < self.inicio:
            raise ValueError(f"la póliza {self.numero} termina antes de empezar")

    def cubre(self, dia: date) -> bool:
        return self.inicio <= dia <= self.fin

    def estado(self, hoy: date) -> EstadoPoliza:
        if hoy < self.inicio:
            return EstadoPoliza.POR_INICIAR
        if hoy > self.fin:
            return EstadoPoliza.VENCIDA
        return EstadoPoliza.VIGENTE

    @property
    def emitida(self) -> bool:
        return self.id_suscripcion is not None


@dataclass(frozen=True, slots=True)
class PlantillaDePoliza:
    """Póliza de prueba que tiene cualquier cliente de *staging*.

    Sirve para que una prueba de extremo a extremo que registra una cuenta nueva tenga sobre
    qué reportar un siniestro. Cada cliente recibe la suya, con número propio.
    """

    numero: str
    producto: str
    inicio: date
    fin: date

    def para(self, id_cliente: str) -> Poliza:
        sufijo = id_cliente.rsplit("-", 1)[-1]
        return Poliza(f"{self.numero}-{sufijo}", id_cliente, self.producto, self.inicio, self.fin)


@dataclass(frozen=True, slots=True)
class CondicionesDeCatalogo:
    """Condiciones de un producto según el catálogo. Completan las pólizas de prueba de la
    semilla, que no vienen de una oferta aceptada (WEB-F04, criterio 4)."""

    nombre_producto: str
    coberturas: tuple[CoberturaPoliza, ...]
    deducible: str
    valor_asegurado: str
    prima_mensual: Dinero


@dataclass(frozen=True, slots=True)
class SolicitudDeEmision:
    """Lo que suscripción entrega para emitir: la oferta aceptada, pagada y firmada."""

    id_suscripcion: str
    id_cliente: str
    producto: str
    nombre_producto: str
    coberturas: tuple[CoberturaPoliza, ...]
    deducible: str
    valor_asegurado: str
    prima_mensual: Dinero
    id_firma: str
    canal: str


def vigencia_anual(inicio: date) -> date:
    """Último día de una póliza anual que empieza en `inicio`, incluido.

    Del 8 de noviembre de 2026 al 7 de noviembre de 2027. Una póliza que empieza un 29 de
    febrero termina el 28 de febrero del año siguiente.
    """
    try:
        aniversario = inicio.replace(year=inicio.year + 1)
    except ValueError:
        aniversario = inicio.replace(year=inicio.year + 1, day=28) + timedelta(days=1)
    return aniversario - timedelta(days=1)
