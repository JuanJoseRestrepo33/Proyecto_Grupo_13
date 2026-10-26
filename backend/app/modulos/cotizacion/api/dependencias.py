"""Composición del módulo de cotización.

Es el único lugar donde se decide qué implementación concreta cumple cada puerto. Con
`COTIZACION_DATABASE_URL` definida, las cotizaciones viven en PostgreSQL, en el esquema
propio del módulo; sin ella, en memoria, que es lo que usan el desarrollo local y las
pruebas unitarias.
"""

from __future__ import annotations

import logging
import os

from app.compartido.modulo import EstadoDeDependencia
from app.compartido.reloj import Reloj, RelojDelSistema
from app.compartido.unica import instancia_unica
from app.modulos import auditoria, consentimiento, perfilamiento
from app.modulos.cotizacion.aplicacion.servicio import ServicioDeCotizacion
from app.modulos.cotizacion.dominio.puertos import RepositorioCotizaciones
from app.modulos.cotizacion.infraestructura.auditoria_nucleo import (
    RegistroDeDecisionesEnAuditoria,
)
from app.modulos.cotizacion.infraestructura.consentimiento_nucleo import ConsentimientoDelNucleo
from app.modulos.cotizacion.infraestructura.perfilamiento_nucleo import PerfilamientoDelNucleo
from app.modulos.cotizacion.infraestructura.repositorio_memoria import RepositorioEnMemoria

VARIABLE_DSN = "COTIZACION_DATABASE_URL"

log = logging.getLogger(__name__)


def _crear_repositorio() -> RepositorioCotizaciones:
    dsn = os.environ.get(VARIABLE_DSN)
    if not dsn:
        return RepositorioEnMemoria()
    from app.modulos.cotizacion.infraestructura.repositorio_postgres import RepositorioPostgres

    repositorio = RepositorioPostgres(dsn)
    try:
        repositorio.preparar()
    except Exception:  # noqa: BLE001 - la base caída al arrancar no debe tumbar el núcleo
        # Como en la auditoría, no se cae a memoria en silencio: una cotización que se
        # pierde con la siguiente revisión no cumple el criterio 5. `/salud` lo reporta y
        # la primera operación reintenta preparar el esquema.
        log.exception("No se pudo preparar el esquema de cotización; se reintentará")
    return repositorio


@instancia_unica
def _componentes() -> tuple[ServicioDeCotizacion, Reloj, RepositorioCotizaciones]:
    reloj = RelojDelSistema()
    repositorio = _crear_repositorio()
    servicio = ServicioDeCotizacion(
        repositorio=repositorio,
        consentimiento=ConsentimientoDelNucleo(consentimiento.obtener_servicio()),
        perfilamiento=PerfilamientoDelNucleo(perfilamiento.obtener_servicio()),
        auditoria=RegistroDeDecisionesEnAuditoria(auditoria.obtener_servicio()),
        reloj=reloj,
    )
    return servicio, reloj, repositorio


def obtener_servicio() -> ServicioDeCotizacion:
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
            "repositorio_cotizaciones", "simulado", f"En memoria; define {VARIABLE_DSN}"
        )
    try:
        repositorio.verificar_conexion()  # type: ignore[attr-defined]
    except Exception as error:  # noqa: BLE001 - cualquier fallo de conexión es "caído"
        return EstadoDeDependencia("repositorio_cotizaciones", "caido", type(error).__name__)
    return EstadoDeDependencia("repositorio_cotizaciones", "ok", "PostgreSQL, esquema cotizacion")


def estado_dependencias() -> tuple[EstadoDeDependencia, ...]:
    """Lo que `/salud` reporta de este módulo.

    Auditoría (ARQ-03), consentimiento (WEB-F02) y perfilamiento (ARQ-02) ya son los
    módulos reales; su propio estado lo reporta cada uno.
    """
    return (
        _estado_repositorio(),
        EstadoDeDependencia(
            "consentimiento", "ok", "Módulo de consentimiento del núcleo (WEB-F02)"
        ),
        EstadoDeDependencia("perfilamiento", "ok", "Módulo de perfilamiento del núcleo (ARQ-02)"),
        EstadoDeDependencia("auditoria", "ok", "Módulo de auditoría del núcleo (ARQ-03)"),
    )
