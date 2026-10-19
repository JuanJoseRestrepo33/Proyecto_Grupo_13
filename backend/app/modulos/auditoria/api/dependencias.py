"""Composición del módulo de auditoría.

Con `AUDITORIA_DATABASE_URL` definida, el log vive en PostgreSQL con las tres capas de
protección; sin ella, en memoria, que es lo que usan el desarrollo local y las pruebas
unitarias. En ambos casos la escritura se difiere a un hilo propio.
"""

from __future__ import annotations

import logging
import os

from app.compartido.modulo import EstadoDeDependencia
from app.compartido.unica import instancia_unica

from ..aplicacion.servicio import ServicioDeAuditoria
from ..dominio.puertos import AlmacenDeRegistros
from ..infraestructura.almacen_memoria import AlmacenEnMemoria
from ..infraestructura.cola import ColaDeEscritura

VARIABLE_DSN = "AUDITORIA_DATABASE_URL"

log = logging.getLogger(__name__)


def _crear_almacen() -> AlmacenDeRegistros:
    dsn = os.environ.get(VARIABLE_DSN)
    if not dsn:
        return AlmacenEnMemoria()
    from ..infraestructura.almacen_postgres import AlmacenPostgres

    almacen = AlmacenPostgres(dsn)
    try:
        almacen.preparar()
    except Exception:  # noqa: BLE001 - la base caída al arrancar no debe tumbar el núcleo
        # No se cae a memoria a propósito: un log de auditoría que cambia de sitio en
        # silencio no sirve. `/salud` lo reporta como caído y la escritura reintenta
        # preparar el esquema cuando la base vuelva.
        log.exception("No se pudo preparar el esquema de auditoría; se reintentará")
    return almacen


@instancia_unica
def _componentes() -> tuple[ServicioDeAuditoria, ColaDeEscritura, AlmacenDeRegistros]:
    almacen = _crear_almacen()
    cola = ColaDeEscritura()
    return ServicioDeAuditoria(almacen, ejecutor=cola), cola, almacen


def obtener_servicio() -> ServicioDeAuditoria:
    return _componentes()[0]


def vaciar_escrituras_pendientes() -> None:
    _componentes()[1].vaciar()


def reiniciar() -> None:
    """Descarta la composición actual. Solo para pruebas."""
    _componentes.cache_clear()


def estado_dependencias() -> tuple[EstadoDeDependencia, ...]:
    _, cola, almacen = _componentes()
    if isinstance(almacen, AlmacenEnMemoria):
        estado = EstadoDeDependencia(
            "almacen_auditoria", "simulado", "En memoria; sin protección en base de datos"
        )
    else:
        try:
            almacen.verificar_conexion()  # type: ignore[attr-defined]
            estado = EstadoDeDependencia("almacen_auditoria", "ok", "PostgreSQL, append-only")
        except Exception as error:  # noqa: BLE001 - cualquier fallo de conexión es "caído"
            estado = EstadoDeDependencia("almacen_auditoria", "caido", type(error).__name__)
    pendientes = cola.pendientes
    detalle = f"{pendientes} escrituras pendientes" if pendientes else "al día"
    return (estado, EstadoDeDependencia("cola_escritura", "ok", detalle))
