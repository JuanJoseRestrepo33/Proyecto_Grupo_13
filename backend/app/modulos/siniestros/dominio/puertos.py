"""Puertos del módulo de siniestros.

ConsultaDePolizas     → módulo pólizas, por su interfaz pública (ARQ-01)
RepositorioSiniestros → persistencia del propio módulo
RegistroDeAuditoria   → log de auditoría (ARQ-03), por su interfaz pública
AlmacenDeEvidencias   → archivos de evidencia (MOB-F05), en `almacen_evidencias.py`
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime
from typing import Protocol

from .evidencias import EvidenciaDelSiniestro
from .modelo import EstadoSiniestro, Siniestro
from .seguimiento import EventoDeSeguimiento, Requerimiento


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

    def agregar_evidencia(self, evidencia: EvidenciaDelSiniestro) -> None:
        raise NotImplementedError

    def evidencias(self, id_siniestro: str) -> tuple[EvidenciaDelSiniestro, ...]:
        """Las evidencias del siniestro en el orden en que se cargaron."""
        raise NotImplementedError

    # -- seguimiento (WEB-F06) ---------------------------------------------------------

    def por_id(self, id_siniestro: str) -> Siniestro | None:
        """Sin filtrar por cliente: solo para la operación interna de cambio de estado."""
        raise NotImplementedError

    def cambiar_estado(
        self,
        id_siniestro: str,
        anterior: EstadoSiniestro,
        nuevo: EstadoSiniestro,
        instante: datetime,
    ) -> bool:
        """Cambia el estado solo si sigue siendo `anterior`. Es atómico: dos cambios
        simultáneos sobre el mismo estado dejan uno solo."""
        raise NotImplementedError

    def agregar_evento(self, evento: EventoDeSeguimiento) -> None:
        raise NotImplementedError

    def eventos(self, id_siniestro: str) -> tuple[EventoDeSeguimiento, ...]:
        """Del más antiguo al más reciente."""
        raise NotImplementedError

    def agregar_requerimientos(self, requerimientos: tuple[Requerimiento, ...]) -> None:
        raise NotImplementedError

    def requerimientos(self, id_siniestro: str) -> tuple[Requerimiento, ...]:
        """Del más antiguo al más reciente."""
        raise NotImplementedError

    def atender_requerimiento(
        self, id_requerimiento: str, id_evidencia: str, instante: datetime
    ) -> bool:
        """Lo marca atendido si seguía pendiente. Devuelve si lo atendió."""
        raise NotImplementedError


class RegistroDeAuditoria(Protocol):
    def registrar(self, siniestro: Siniestro) -> str:
        """Deja el reporte en el log con póliza, tipo de evento, canal y marca de tiempo."""
        raise NotImplementedError

    def registrar_evidencia(self, siniestro: Siniestro, evidencia: EvidenciaDelSiniestro) -> str:
        raise NotImplementedError

    def registrar_cambio_de_estado(
        self,
        siniestro: Siniestro,
        evento: EventoDeSeguimiento,
        requerimientos: tuple[str, ...],
    ) -> str:
        raise NotImplementedError
