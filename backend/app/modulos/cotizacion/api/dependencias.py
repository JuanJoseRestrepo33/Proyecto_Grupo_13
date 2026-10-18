"""Composición del módulo de cotización.

Es el único lugar donde se decide qué implementación concreta cumple cada puerto.
Cambiar el repositorio en memoria por PostgreSQL, o el consentimiento simulado por el
módulo real de WEB-F02, se hace aquí y en ningún otro archivo.
"""

from __future__ import annotations

from functools import lru_cache

from app.compartido.modulo import EstadoDeDependencia
from app.compartido.reloj import Reloj, RelojDelSistema
from app.modulos.cotizacion.aplicacion.servicio import ServicioDeCotizacion
from app.modulos.cotizacion.infraestructura.adaptadores_simulados import (
    ConsentimientoSimulado,
    PerfilamientoSimulado,
    RegistroDeDecisionesEnMemoria,
)
from app.modulos.cotizacion.infraestructura.repositorio_memoria import RepositorioEnMemoria


@lru_cache(maxsize=1)
def _componentes() -> tuple[ServicioDeCotizacion, Reloj]:
    reloj = RelojDelSistema()
    servicio = ServicioDeCotizacion(
        repositorio=RepositorioEnMemoria(),
        consentimiento=ConsentimientoSimulado(),
        perfilamiento=PerfilamientoSimulado(),
        auditoria=RegistroDeDecisionesEnMemoria(),
        reloj=reloj,
    )
    return servicio, reloj


def obtener_servicio() -> ServicioDeCotizacion:
    return _componentes()[0]


def obtener_reloj() -> Reloj:
    return _componentes()[1]


def estado_dependencias() -> tuple[EstadoDeDependencia, ...]:
    """Lo que `/salud` reporta de este módulo.

    Hoy todo es simulado o en memoria, y se dice así: cada entrada nombra la historia que
    la vuelve real para que el estado del núcleo no mienta sobre lo que ya está conectado.
    """
    return (
        EstadoDeDependencia(
            "repositorio_cotizaciones", "simulado", "En memoria; PostgreSQL con WEB-F01 cierre"
        ),
        EstadoDeDependencia("consentimiento", "simulado", "Adaptador simulado hasta WEB-F02"),
        EstadoDeDependencia("perfilamiento", "simulado", "Adaptador simulado hasta ARQ-02"),
        EstadoDeDependencia("auditoria", "simulado", "Registro en memoria hasta ARQ-03"),
    )
