"""Composición del módulo de perfilamiento.

El proveedor de Open Finance es el simulado; `PERFILAMIENTO_OPEN_FINANCE_MODO` lo pone en
`sano`, `degradado` o `caido` para ensayar los tres escenarios de EXP-01 sin tocar código.
El proveedor real entra aquí, detrás del mismo puerto. La caché y la suscripción al evento
de revocación son las de WEB-F02.

Los umbrales del circuito (D-02) se configuran sin tocar código (ARQ-05):

    PERFILAMIENTO_CIRCUITO_UMBRAL          tasa de fallos que lo abre, entre 0 y 1 (0.10)
    PERFILAMIENTO_CIRCUITO_VENTANA_S       ventana de observación en segundos (30)
    PERFILAMIENTO_CIRCUITO_MINIMO_LLAMADAS llamadas mínimas en la ventana para evaluar (5)

Un valor inválido no arranca el módulo: un circuito mal configurado es peor que ninguno.
Antes de servir un perfil de la caché, el adaptador verifica el consentimiento contra el
módulo de consentimiento (ARQ-05).
"""

from __future__ import annotations

import logging
import os
from collections.abc import Mapping

from app.compartido.eventos import Evento, obtener_bus
from app.compartido.modulo import EstadoDeDependencia
from app.compartido.unica import instancia_unica
from app.modulos import consentimiento

from ..aplicacion.servicio import ServicioDePerfilamiento
from ..infraestructura.adaptador_resiliente import (
    PRESUPUESTO_USUARIO_S,
    AdaptadorOpenFinanceResiliente,
)
from ..infraestructura.cache_perfiles import CacheDePerfiles
from ..infraestructura.circuit_breaker import CircuitBreaker, EstadoCircuito
from ..infraestructura.consentimiento_nucleo import ConsentimientoDelNucleo
from ..infraestructura.proveedor_simulado import NOMBRE, ProveedorOpenFinanceSimulado

#: Nombre del evento que publica consentimiento. Se repite aquí a propósito: perfilamiento
#: conoce el contrato del evento, no el módulo que lo emite.
EVENTO_CONSENTIMIENTO_REVOCADO = "consentimiento-revocado"

VARIABLE_MODO = "PERFILAMIENTO_OPEN_FINANCE_MODO"
VARIABLE_PRESUPUESTO_MS = "PERFILAMIENTO_PRESUPUESTO_MS"
VARIABLE_UMBRAL = "PERFILAMIENTO_CIRCUITO_UMBRAL"
VARIABLE_VENTANA_S = "PERFILAMIENTO_CIRCUITO_VENTANA_S"
VARIABLE_MINIMO_LLAMADAS = "PERFILAMIENTO_CIRCUITO_MINIMO_LLAMADAS"

log = logging.getLogger(__name__)


@instancia_unica
def obtener_cache() -> CacheDePerfiles:
    return CacheDePerfiles()


def crear_breaker(entorno: Mapping[str, str] | None = None) -> CircuitBreaker:
    """El circuito con los umbrales del entorno; sin ellos, los de D-02 (10 %, 30 s, 5)."""
    entorno = os.environ if entorno is None else entorno
    try:
        umbral = float(entorno.get(VARIABLE_UMBRAL, "0.10"))
        ventana = float(entorno.get(VARIABLE_VENTANA_S, "30"))
        minimo = int(entorno.get(VARIABLE_MINIMO_LLAMADAS, "5"))
    except ValueError as error:
        raise RuntimeError(f"Umbral del circuito no numérico: {error}") from error
    if not 0 < umbral <= 1:
        raise RuntimeError(f"{VARIABLE_UMBRAL} debe estar entre 0 y 1, no {umbral}")
    if ventana <= 0:
        raise RuntimeError(f"{VARIABLE_VENTANA_S} debe ser positiva, no {ventana}")
    if minimo < 1:
        raise RuntimeError(f"{VARIABLE_MINIMO_LLAMADAS} debe ser al menos 1, no {minimo}")
    return CircuitBreaker(umbral_fallos=umbral, ventana_s=ventana, minimo_llamadas=minimo)


@instancia_unica
def _componentes() -> tuple[ServicioDePerfilamiento, AdaptadorOpenFinanceResiliente]:
    proveedor = ProveedorOpenFinanceSimulado(modo=os.environ.get(VARIABLE_MODO, "sano"))
    presupuesto_ms = os.environ.get(VARIABLE_PRESUPUESTO_MS)
    adaptador = AdaptadorOpenFinanceResiliente(
        proveedor,
        obtener_cache(),
        breaker=crear_breaker(),
        verificacion=ConsentimientoDelNucleo(consentimiento.obtener_servicio()),
        presupuesto_s=int(presupuesto_ms) / 1000 if presupuesto_ms else PRESUPUESTO_USUARIO_S,
    )
    return ServicioDePerfilamiento(adaptador), adaptador


def obtener_servicio() -> ServicioDePerfilamiento:
    return _componentes()[0]


def obtener_adaptador() -> AdaptadorOpenFinanceResiliente:
    return _componentes()[1]


def reiniciar() -> None:
    """Descarta la composición actual. Solo para pruebas."""
    _componentes.cache_clear()
    obtener_cache.cache_clear()


def _al_revocar_consentimiento(evento: Evento) -> None:
    id_cliente = str(evento.carga["id_cliente"])
    if obtener_cache().invalidar(id_cliente):
        log.info("Perfil de %s retirado de la caché por revocación", id_cliente)


def iniciar() -> None:
    """Se suscribe al bus. La fachada lo llama al armar la aplicación."""
    obtener_bus().suscribir(EVENTO_CONSENTIMIENTO_REVOCADO, _al_revocar_consentimiento)


def estado_dependencias() -> tuple[EstadoDeDependencia, ...]:
    adaptador = obtener_adaptador()
    circuito = adaptador.breaker.resumen()
    metricas = adaptador.metricas.resumen()
    detalle = (
        f"{NOMBRE} · circuito {circuito['estado']} · p95 {metricas['p95_ms']} ms · "
        f"error {metricas['tasa_error']:.0%}"
    )
    if adaptador.breaker.estado is EstadoCircuito.ABIERTO:
        estado_of = EstadoDeDependencia(
            "open_finance", "degradado", detalle + " · sirviendo de caché"
        )
    else:
        estado_of = EstadoDeDependencia("open_finance", "simulado", detalle)

    suscrito = obtener_bus().suscriptores(EVENTO_CONSENTIMIENTO_REVOCADO) > 0
    return (
        EstadoDeDependencia(
            "cache_perfiles",
            "ok" if suscrito else "degradado",
            f"{obtener_cache().tamano} perfiles; "
            + ("escucha revocaciones" if suscrito else "sin suscripción a revocaciones"),
        ),
        estado_of,
    )
