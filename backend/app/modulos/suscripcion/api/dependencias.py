"""Composición del módulo de suscripción.

Con `SUSCRIPCION_DATABASE_URL` las suscripciones viven en PostgreSQL, en el esquema propio
del módulo; sin ella, en memoria (desarrollo y pruebas). La firma electrónica es la simulada
y se configura con `FIRMA_MODO` (`sana` o `caida`).
"""

from __future__ import annotations

import logging
import os

from app.compartido.modulo import EstadoDeDependencia
from app.compartido.reloj import RelojDelSistema
from app.compartido.unica import instancia_unica
from app.modulos import auditoria, cotizacion, pagos, polizas

from ..aplicacion.servicio import ServicioDeSuscripcion
from ..dominio.puertos import RepositorioSuscripciones
from ..infraestructura.auditoria_nucleo import RegistroEnAuditoria
from ..infraestructura.firma_simulada import FirmaSimulada
from ..infraestructura.modulos_nucleo import CobrosDelNucleo, EmisionDelNucleo, OfertasDelNucleo
from ..infraestructura.repositorio_memoria import RepositorioEnMemoria

VARIABLE_DSN = "SUSCRIPCION_DATABASE_URL"

log = logging.getLogger(__name__)


def _crear_repositorio() -> RepositorioSuscripciones:
    dsn = os.environ.get(VARIABLE_DSN)
    if not dsn:
        return RepositorioEnMemoria()
    from ..infraestructura.repositorio_postgres import RepositorioPostgres

    repositorio = RepositorioPostgres(dsn)
    try:
        repositorio.preparar()
    except Exception:  # noqa: BLE001 - la base caída al arrancar no debe tumbar el núcleo
        log.exception("No se pudo preparar el esquema de suscripción; se reintentará")
    return repositorio


@instancia_unica
def _componentes() -> tuple[ServicioDeSuscripcion, RepositorioSuscripciones, FirmaSimulada]:
    reloj = RelojDelSistema()
    repositorio = _crear_repositorio()
    firma = FirmaSimulada.desde_entorno(reloj, os.environ)
    servicio = ServicioDeSuscripcion(
        repositorio=repositorio,
        ofertas=OfertasDelNucleo(cotizacion.obtener_servicio()),
        cobros=CobrosDelNucleo(pagos.obtener_servicio()),
        firma=firma,
        emision=EmisionDelNucleo(polizas.obtener_servicio()),
        auditoria=RegistroEnAuditoria(auditoria.obtener_servicio()),
        reloj=reloj,
    )
    return servicio, repositorio, firma


def obtener_servicio() -> ServicioDeSuscripcion:
    return _componentes()[0]


def obtener_firma() -> FirmaSimulada:
    """El proveedor de firma simulado del proceso. Solo para pruebas."""
    return _componentes()[2]


def reiniciar() -> None:
    """Descarta la composición actual. Solo para pruebas."""
    _componentes.cache_clear()


def _estado_repositorio() -> EstadoDeDependencia:
    repositorio = _componentes()[1]
    if isinstance(repositorio, RepositorioEnMemoria):
        return EstadoDeDependencia(
            "repositorio_suscripciones", "simulado", f"En memoria; define {VARIABLE_DSN}"
        )
    try:
        repositorio.verificar_conexion()  # type: ignore[attr-defined]
    except Exception as error:  # noqa: BLE001 - cualquier fallo de conexión es "caído"
        return EstadoDeDependencia("repositorio_suscripciones", "caido", type(error).__name__)
    return EstadoDeDependencia("repositorio_suscripciones", "ok", "PostgreSQL, esquema suscripcion")


def _estado_firma() -> EstadoDeDependencia:
    if _componentes()[2].caida:
        return EstadoDeDependencia("firma_electronica", "caido", "FIRMA_MODO=caida")
    return EstadoDeDependencia("firma_electronica", "simulado", "Proveedor de firma simulado")


def estado_dependencias() -> tuple[EstadoDeDependencia, ...]:
    return (
        _estado_repositorio(),
        _estado_firma(),
        EstadoDeDependencia("cotizacion", "ok", "Módulo de cotización del núcleo (WEB-F01)"),
        EstadoDeDependencia("pagos", "ok", "Módulo de pagos del núcleo (WEB-F03)"),
        EstadoDeDependencia("polizas", "ok", "Módulo de pólizas del núcleo (WEB-F04)"),
    )
