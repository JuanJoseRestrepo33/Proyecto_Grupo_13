"""Composición del módulo de consentimiento.

Con `CONSENTIMIENTO_DATABASE_URL` los consentimientos viven en PostgreSQL, en el esquema
propio del módulo, y todas las réplicas ven el mismo estado (ARQ-05). Sin ella, en memoria:
válido con una sola instancia del núcleo. La auditoría y el bus ya son los reales.
"""

from __future__ import annotations

import logging
import os

from app.compartido.eventos import obtener_bus
from app.compartido.modulo import EstadoDeDependencia
from app.compartido.reloj import Reloj, RelojDelSistema
from app.compartido.unica import instancia_unica
from app.modulos import auditoria

from ..aplicacion.servicio import ServicioDeConsentimiento
from ..dominio.puertos import RepositorioConsentimientos
from ..infraestructura.auditoria_nucleo import RegistroEnAuditoria
from ..infraestructura.repositorio_memoria import RepositorioEnMemoria

VARIABLE_DSN = "CONSENTIMIENTO_DATABASE_URL"

log = logging.getLogger(__name__)


def _crear_repositorio() -> RepositorioConsentimientos:
    dsn = os.environ.get(VARIABLE_DSN)
    if not dsn:
        return RepositorioEnMemoria()
    from ..infraestructura.repositorio_postgres import RepositorioPostgres

    repositorio = RepositorioPostgres(dsn)
    try:
        repositorio.preparar()
    except Exception:  # noqa: BLE001 - la base caída al arrancar no debe tumbar el núcleo
        log.exception("No se pudo preparar el esquema de consentimiento; se reintentará")
    return repositorio


@instancia_unica
def _componentes() -> tuple[ServicioDeConsentimiento, Reloj, RepositorioConsentimientos]:
    reloj = RelojDelSistema()
    repositorio = _crear_repositorio()
    servicio = ServicioDeConsentimiento(
        repositorio=repositorio,
        auditoria=RegistroEnAuditoria(auditoria.obtener_servicio()),
        bus=obtener_bus(),
        reloj=reloj,
    )
    return servicio, reloj, repositorio


def obtener_servicio() -> ServicioDeConsentimiento:
    return _componentes()[0]


def obtener_reloj() -> Reloj:
    return _componentes()[1]


def reiniciar() -> None:
    """Descarta la composición actual. Solo para pruebas."""
    _componentes.cache_clear()


def _estado_repositorio() -> EstadoDeDependencia:
    repositorio = _componentes()[2]
    if isinstance(repositorio, RepositorioEnMemoria):
        return EstadoDeDependencia(
            "repositorio_consentimientos", "simulado", f"En memoria; define {VARIABLE_DSN}"
        )
    try:
        repositorio.verificar_conexion()  # type: ignore[attr-defined]
    except Exception as error:  # noqa: BLE001 - cualquier fallo de conexión es "caído"
        return EstadoDeDependencia("repositorio_consentimientos", "caido", type(error).__name__)
    return EstadoDeDependencia(
        "repositorio_consentimientos", "ok", "PostgreSQL, esquema consentimiento"
    )


def estado_dependencias() -> tuple[EstadoDeDependencia, ...]:
    return (
        _estado_repositorio(),
        EstadoDeDependencia("auditoria", "ok", "Módulo de auditoría del núcleo (ARQ-03)"),
        EstadoDeDependencia("bus_eventos", "ok", "En proceso; canal de EXP-02 pendiente"),
    )
