"""Composición del módulo de cotización.

Es el único lugar donde se decide qué implementación concreta cumple cada puerto.
Cambiar el repositorio en memoria por PostgreSQL, o el consentimiento simulado por el
módulo real de WEB-F02, se hace aquí y en ningún otro archivo.
"""

from __future__ import annotations

from functools import lru_cache

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
