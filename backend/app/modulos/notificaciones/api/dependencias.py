"""Composición del módulo de notificaciones.

Con `NOTIFICACIONES_DATABASE_URL` los dispositivos y las notificaciones viven en PostgreSQL,
en el esquema propio del módulo; sin ella, en memoria. El proveedor de push es el simulado
(`PUSH_MODO` = `sano` o `caido`).
"""

from __future__ import annotations

import logging
import os

from app.compartido.eventos import Evento, obtener_bus
from app.compartido.modulo import EstadoDeDependencia
from app.compartido.reloj import RelojDelSistema
from app.compartido.unica import instancia_unica
from app.modulos import siniestros

from ..aplicacion.servicio import ServicioDeNotificaciones
from ..infraestructura.proveedor_simulado import ProveedorPushSimulado
from ..infraestructura.repositorio_memoria import DispositivosEnMemoria, NotificacionesEnMemoria

VARIABLE_DSN = "NOTIFICACIONES_DATABASE_URL"

log = logging.getLogger(__name__)


def _crear_repositorios():
    dsn = os.environ.get(VARIABLE_DSN)
    if not dsn:
        return DispositivosEnMemoria(), NotificacionesEnMemoria()
    from ..infraestructura.repositorio_postgres import DispositivosPostgres, NotificacionesPostgres

    dispositivos, notificaciones = DispositivosPostgres(dsn), NotificacionesPostgres(dsn)
    try:
        dispositivos.preparar()
    except Exception:  # noqa: BLE001 - la base caída al arrancar no debe tumbar el núcleo
        log.exception("No se pudo preparar el esquema de notificaciones; se reintentará")
    return dispositivos, notificaciones


@instancia_unica
def _componentes():
    dispositivos, notificaciones = _crear_repositorios()
    proveedor = ProveedorPushSimulado.desde_entorno(os.environ)
    servicio = ServicioDeNotificaciones(dispositivos, notificaciones, proveedor, RelojDelSistema())
    return servicio, dispositivos, proveedor


def obtener_servicio() -> ServicioDeNotificaciones:
    return _componentes()[0]


def obtener_proveedor() -> ProveedorPushSimulado:
    """El proveedor simulado del proceso. Solo para pruebas."""
    return _componentes()[2]


def reiniciar() -> None:
    """Descarta la composición actual. Solo para pruebas."""
    _componentes.cache_clear()


def _al_cambiar_estado(evento: Evento) -> None:
    obtener_servicio().al_cambiar_estado(evento)


def iniciar() -> None:
    """Se suscribe al cambio de estado de los siniestros. La fachada lo llama al arrancar."""
    obtener_bus().suscribir(siniestros.EVENTO_SINIESTRO_ESTADO_CAMBIADO, _al_cambiar_estado)


def _estado_repositorio() -> EstadoDeDependencia:
    dispositivos = _componentes()[1]
    if isinstance(dispositivos, DispositivosEnMemoria):
        return EstadoDeDependencia(
            "repositorio_notificaciones", "simulado", f"En memoria; define {VARIABLE_DSN}"
        )
    try:
        dispositivos.verificar_conexion()
    except Exception as error:  # noqa: BLE001 - cualquier fallo de conexión es "caído"
        return EstadoDeDependencia("repositorio_notificaciones", "caido", type(error).__name__)
    return EstadoDeDependencia(
        "repositorio_notificaciones", "ok", "PostgreSQL, esquema notificaciones"
    )


def _estado_proveedor() -> EstadoDeDependencia:
    if _componentes()[2].caido:
        return EstadoDeDependencia("proveedor_push", "caido", "PUSH_MODO=caido")
    return EstadoDeDependencia("proveedor_push", "simulado", "Proveedor de push simulado")


def estado_dependencias() -> tuple[EstadoDeDependencia, ...]:
    return (_estado_repositorio(), _estado_proveedor())
