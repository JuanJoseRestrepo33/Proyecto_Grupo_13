"""Puertos del módulo de siniestros.

ConsultaDePolizas     → módulo pólizas, por su interfaz pública (ARQ-01)
RepositorioSiniestros → persistencia del propio módulo
RegistroDeAuditoria   → log de auditoría (ARQ-03), por su interfaz pública
AlmacenDeEvidencias   → archivos de evidencia (MOB-F05), en `almacen_evidencias.py`
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from typing import Protocol

from .modelo import Siniestro


@dataclass(frozen=True, slots=True)
class VigenciaDePoliza:
    numero: str
    producto: str
    inicio: date
    fin: date

    def cubre(self, dia: date) -> bool:
        return self.inicio <= dia <= self.fin


class ConsultaDePolizas(Protocol):
    def poliza_del_cliente(self, numero: str, id_cliente: str) -> VigenciaDePoliza | None:
        """La póliza si existe y es del cliente; None en cualquier otro caso."""
        raise NotImplementedError


class RepositorioSiniestros(Protocol):
    def guardar_si_nuevo(self, siniestro: Siniestro) -> Siniestro:
        """Guarda el siniestro, salvo que el cliente ya tenga uno con la misma clave de
        idempotencia: entonces no guarda nada y devuelve ese. Es atómico."""
        raise NotImplementedError

    def obtener(self, id_cliente: str, id_siniestro: str) -> Siniestro | None:
        raise NotImplementedError

    def del_cliente(self, id_cliente: str) -> tuple[Siniestro, ...]:
        """Del más reciente al más antiguo."""
        raise NotImplementedError

    def siguiente_referencia(self, anio: int) -> str:
        """Número que ve el cliente, con el formato SIN-AAAA-NNNNNN."""
        raise NotImplementedError


class RegistroDeAuditoria(Protocol):
    def registrar(self, siniestro: Siniestro) -> str:
        """Deja el reporte en el log con póliza, tipo de evento, canal y marca de tiempo."""
        raise NotImplementedError
