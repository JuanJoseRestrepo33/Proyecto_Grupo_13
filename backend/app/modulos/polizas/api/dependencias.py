"""Composición del módulo de pólizas.

Con `POLIZAS_SEMILLA` (ruta a un JSON) carga las pólizas de prueba al arrancar; sin ella no
hay pólizas, que es lo correcto en producción hasta que WEB-F03 emita las reales. Una
semilla ilegible no tumba el núcleo: `/salud` lo reporta.
"""

from __future__ import annotations

import logging
import os

from app.compartido.modulo import EstadoDeDependencia
from app.compartido.reloj import Reloj, RelojDelSistema
from app.compartido.unica import instancia_unica

from ..aplicacion.servicio import ServicioDePolizas
from ..infraestructura import semilla
from ..infraestructura.repositorio_memoria import RepositorioEnMemoria

VARIABLE_SEMILLA = "POLIZAS_SEMILLA"

log = logging.getLogger(__name__)


@instancia_unica
def _componentes() -> tuple[ServicioDePolizas, Reloj, EstadoDeDependencia]:
    reloj = RelojDelSistema()
    repositorio = RepositorioEnMemoria()
    ruta = os.environ.get(VARIABLE_SEMILLA)
    cargada = semilla.Semilla()
    if not ruta:
        estado = EstadoDeDependencia("semilla_polizas", "ok", "Sin pólizas de prueba")
    else:
        try:
            cargada = semilla.cargar(ruta, reloj.ahora().date())
            detalle = (
                f"{len(cargada.polizas)} pólizas y {len(cargada.plantillas)} plantillas de prueba"
            )
            estado = EstadoDeDependencia("semilla_polizas", "simulado", detalle)
        except semilla.SemillaInvalidaError as error:
            log.error("Semilla de pólizas inválida: %s", error)
            estado = EstadoDeDependencia("semilla_polizas", "degradado", str(error))
    for poliza in cargada.polizas:
        repositorio.guardar(poliza)
    servicio = ServicioDePolizas(repositorio, reloj, cargada.plantillas)
    return servicio, reloj, estado


def obtener_servicio() -> ServicioDePolizas:
    return _componentes()[0]


def obtener_reloj() -> Reloj:
    return _componentes()[1]


def reiniciar() -> None:
    """Descarta la composición actual. Solo para pruebas."""
    _componentes.cache_clear()


def estado_dependencias() -> tuple[EstadoDeDependencia, ...]:
    return (
        EstadoDeDependencia(
            "repositorio_polizas", "simulado", "En memoria; las reales llegan con WEB-F03"
        ),
        _componentes()[2],
    )
