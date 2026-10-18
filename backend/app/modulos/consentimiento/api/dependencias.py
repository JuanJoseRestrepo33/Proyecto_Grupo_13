"""Composición del módulo de consentimiento.

El repositorio es en memoria, como el de cotización, hasta que llegue la persistencia por
contexto. La auditoría y el bus ya son los reales.
"""

from __future__ import annotations

from functools import lru_cache

from app.compartido.eventos import obtener_bus
from app.compartido.modulo import EstadoDeDependencia
from app.compartido.reloj import Reloj, RelojDelSistema
from app.modulos import auditoria

from ..aplicacion.servicio import ServicioDeConsentimiento
from ..infraestructura.auditoria_nucleo import RegistroEnAuditoria
from ..infraestructura.repositorio_memoria import RepositorioEnMemoria


@lru_cache(maxsize=1)
def _componentes() -> tuple[ServicioDeConsentimiento, Reloj]:
    reloj = RelojDelSistema()
    servicio = ServicioDeConsentimiento(
        repositorio=RepositorioEnMemoria(),
        auditoria=RegistroEnAuditoria(auditoria.obtener_servicio()),
        bus=obtener_bus(),
        reloj=reloj,
    )
    return servicio, reloj


def obtener_servicio() -> ServicioDeConsentimiento:
    return _componentes()[0]


def obtener_reloj() -> Reloj:
    return _componentes()[1]


def reiniciar() -> None:
    """Descarta la composición actual. Solo para pruebas."""
    _componentes.cache_clear()


def estado_dependencias() -> tuple[EstadoDeDependencia, ...]:
    return (
        EstadoDeDependencia(
            "repositorio_consentimientos",
            "simulado",
            "En memoria; persistencia por contexto pendiente",
        ),
        EstadoDeDependencia("auditoria", "ok", "Módulo de auditoría del núcleo (ARQ-03)"),
        EstadoDeDependencia("bus_eventos", "ok", "En proceso; canal de EXP-02 pendiente"),
    )
