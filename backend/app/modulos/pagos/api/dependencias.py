"""Composición del módulo de pagos.

Con `PAGOS_DATABASE_URL` los pagos viven en PostgreSQL, en el esquema propio del módulo; sin
ella, en memoria (desarrollo y pruebas). La pasarela es siempre la simulada y se configura
con las variables `PASARELA_*` (ver `infraestructura/pasarela_simulada.py`).
"""

from __future__ import annotations

import logging
import os

from app.compartido.modulo import EstadoDeDependencia
from app.compartido.reloj import RelojDelSistema
from app.compartido.unica import instancia_unica
from app.modulos import auditoria

from ..aplicacion.servicio import ServicioDePagos
from ..dominio.puertos import RepositorioPagos
from ..infraestructura.auditoria_nucleo import RegistroEnAuditoria
from ..infraestructura.pasarela_simulada import PasarelaSimulada
from ..infraestructura.repositorio_memoria import RepositorioEnMemoria

VARIABLE_DSN = "PAGOS_DATABASE_URL"

log = logging.getLogger(__name__)


def _crear_repositorio() -> RepositorioPagos:
    dsn = os.environ.get(VARIABLE_DSN)
    if not dsn:
        return RepositorioEnMemoria()
    from ..infraestructura.repositorio_postgres import RepositorioPostgres

    repositorio = RepositorioPostgres(dsn)
    try:
        repositorio.preparar()
    except Exception:  # noqa: BLE001 - la base caída al arrancar no debe tumbar el núcleo
        log.exception("No se pudo preparar el esquema de pagos; se reintentará")
    return repositorio


@instancia_unica
def _componentes() -> tuple[ServicioDePagos, RepositorioPagos, PasarelaSimulada]:
    repositorio = _crear_repositorio()
    pasarela = PasarelaSimulada.desde_entorno(os.environ)
    servicio = ServicioDePagos(
        repositorio=repositorio,
        pasarela=pasarela,
        auditoria=RegistroEnAuditoria(auditoria.obtener_servicio()),
        reloj=RelojDelSistema(),
    )
    return servicio, repositorio, pasarela


def validar_configuracion() -> None:
    """Revisa las variables `PASARELA_*` al armar el núcleo: un valor inválido impide arrancar,
    en vez de fallar en la primera solicitud (y en `/salud`)."""
    PasarelaSimulada.desde_entorno(os.environ)


def obtener_servicio() -> ServicioDePagos:
    return _componentes()[0]


def obtener_pasarela() -> PasarelaSimulada:
    """La pasarela simulada del proceso. Solo para pruebas."""
    return _componentes()[2]


def reiniciar() -> None:
    """Descarta la composición actual. Solo para pruebas."""
    _componentes.cache_clear()


def _estado_repositorio() -> EstadoDeDependencia:
    repositorio = _componentes()[1]
    if isinstance(repositorio, RepositorioEnMemoria):
        return EstadoDeDependencia(
            "repositorio_pagos", "simulado", f"En memoria; define {VARIABLE_DSN}"
        )
    try:
        repositorio.verificar_conexion()  # type: ignore[attr-defined]
    except Exception as error:  # noqa: BLE001 - cualquier fallo de conexión es "caído"
        return EstadoDeDependencia("repositorio_pagos", "caido", type(error).__name__)
    return EstadoDeDependencia("repositorio_pagos", "ok", "PostgreSQL, esquema pagos")


def _estado_pasarela() -> EstadoDeDependencia:
    if _componentes()[2].caida:
        return EstadoDeDependencia("pasarela_pagos", "caido", "PASARELA_MODO=caida")
    return EstadoDeDependencia("pasarela_pagos", "simulado", "Pasarela simulada")


def estado_dependencias() -> tuple[EstadoDeDependencia, ...]:
    return (
        _estado_repositorio(),
        _estado_pasarela(),
        EstadoDeDependencia("auditoria", "ok", "Módulo de auditoría del núcleo (ARQ-03)"),
    )
