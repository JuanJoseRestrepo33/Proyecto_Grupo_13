"""Composición del módulo de perfilamiento.

Por ahora solo la caché y su suscripción al evento de revocación. El adaptador de Open
Finance con circuit breaker llega con ARQ-02 y se enchufa aquí.
"""

from __future__ import annotations

import logging

from app.compartido.eventos import Evento, obtener_bus
from app.compartido.modulo import EstadoDeDependencia
from app.compartido.unica import instancia_unica

from ..infraestructura.cache_perfiles import CacheDePerfiles

#: Nombre del evento que publica consentimiento. Se repite aquí a propósito: perfilamiento
#: conoce el contrato del evento, no el módulo que lo emite.
EVENTO_CONSENTIMIENTO_REVOCADO = "consentimiento-revocado"

log = logging.getLogger(__name__)


@instancia_unica
def obtener_cache() -> CacheDePerfiles:
    return CacheDePerfiles()


def _al_revocar_consentimiento(evento: Evento) -> None:
    id_cliente = str(evento.carga["id_cliente"])
    if obtener_cache().invalidar(id_cliente):
        log.info("Perfil de %s retirado de la caché por revocación", id_cliente)


def iniciar() -> None:
    """Se suscribe al bus. La fachada lo llama al armar la aplicación."""
    obtener_bus().suscribir(EVENTO_CONSENTIMIENTO_REVOCADO, _al_revocar_consentimiento)


def estado_dependencias() -> tuple[EstadoDeDependencia, ...]:
    suscrito = obtener_bus().suscriptores(EVENTO_CONSENTIMIENTO_REVOCADO) > 0
    return (
        EstadoDeDependencia(
            "cache_perfiles",
            "ok" if suscrito else "degradado",
            f"{obtener_cache().tamano} perfiles; "
            + ("escucha revocaciones" if suscrito else "sin suscripción a revocaciones"),
        ),
        EstadoDeDependencia("open_finance", "simulado", "Adaptador con circuit breaker en ARQ-02"),
    )
