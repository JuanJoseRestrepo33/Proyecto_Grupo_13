"""Modelo de la póliza, en lo que otros módulos necesitan saber de ella.

Por ahora es lo que pide el reporte de siniestros (WEB-F05): de quién es, de qué producto y
entre qué fechas cubre. La consulta completa con coberturas, deducibles y documento es de
WEB-F04 y se construye sobre esto.

La vigencia va por días calendario, con los dos extremos incluidos: una póliza que cubre del
1 al 31 cubre un evento ocurrido el 31.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from enum import StrEnum


class EstadoPoliza(StrEnum):
    VIGENTE = "vigente"
    VENCIDA = "vencida"
    POR_INICIAR = "por_iniciar"


@dataclass(frozen=True, slots=True)
class Poliza:
    numero: str
    id_cliente: str
    producto: str
    inicio: date
    fin: date

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
