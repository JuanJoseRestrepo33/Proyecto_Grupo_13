"""Seguimiento del siniestro: cambios de estado, historial y requerimientos (WEB-F06).

Los estados y las transiciones que admite el núcleo:

    reportado ──▶ en_evaluacion ──▶ aprobado ──▶ pagado
        │              │  ▲
        │              ▼  │
        ├────────▶ requiere_informacion
        │              │
        └──────────────┴──────────▶ rechazado

`rechazado` y `pagado` son finales. Todo cambio deja un evento en el historial con su fecha
(criterio 4), una entrada en el log de auditoría y un evento en el bus que consume MOB-F06
(criterio 3). Pasar a `requiere_informacion` exige decir qué falta: un requerimiento que el
cliente ve en los dos canales y responde adjuntando evidencia (criterio 5).

El cambio de estado lo hace el núcleo, no el cliente: en este prototipo no hay interfaz de
operador, así que existe una operación interna para *staging* y pruebas.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime

from app.compartido.errores import DatoInvalido, RecursoNoEncontrado, ReglaDeNegocioViolada

from .modelo import EstadoSiniestro

E = EstadoSiniestro

TRANSICIONES: dict[EstadoSiniestro, frozenset[EstadoSiniestro]] = {
    E.REPORTADO: frozenset({E.EN_EVALUACION, E.REQUIERE_INFORMACION, E.RECHAZADO}),
    E.EN_EVALUACION: frozenset({E.REQUIERE_INFORMACION, E.APROBADO, E.RECHAZADO}),
    E.REQUIERE_INFORMACION: frozenset({E.EN_EVALUACION, E.RECHAZADO}),
    E.APROBADO: frozenset({E.PAGADO}),
    E.RECHAZADO: frozenset(),
    E.PAGADO: frozenset(),
}

#: Quién cambió el estado. Hoy solo la operación interna; mañana, el ajustador.
ACTOR_OPERADOR = "operador"
#: El evento inicial del historial, que no viene de un cambio sino del reporte.
ACTOR_CLIENTE = "cliente"

LARGO_MAXIMO_MOTIVO = 500
LARGO_MAXIMO_REQUERIMIENTO = 300
MAXIMO_REQUERIMIENTOS = 10


class TransicionNoPermitidaError(ReglaDeNegocioViolada):
    codigo = "transicion_no_permitida"


class CambioConcurrenteError(ReglaDeNegocioViolada):
    """Otro cambio llegó primero: el estado ya no es el que se leyó."""

    codigo = "cambio_de_estado_concurrente"


class RequerimientosObligatoriosError(DatoInvalido):
    codigo = "requerimientos_obligatorios"


class RequerimientoNoEncontradoError(RecursoNoEncontrado):
    codigo = "requerimiento_no_encontrado"


class RequerimientoYaAtendidoError(ReglaDeNegocioViolada):
    codigo = "requerimiento_ya_atendido"


def exigir_transicion(anterior: EstadoSiniestro, nuevo: EstadoSiniestro) -> None:
    if nuevo not in TRANSICIONES[anterior]:
        raise TransicionNoPermitidaError(
            f"Un siniestro {anterior} no puede pasar a {nuevo}",
            estado_actual=str(anterior),
            estado_pedido=str(nuevo),
            permitidos=sorted(str(e) for e in TRANSICIONES[anterior]),
        )


@dataclass(frozen=True, slots=True)
class EventoDeSeguimiento:
    id_evento: str
    id_siniestro: str
    estado_anterior: EstadoSiniestro | None
    estado_nuevo: EstadoSiniestro
    ocurrido_en: datetime
    actor: str
    motivo: str | None = None
    #: Entrada del log de auditoría del cambio; None en el evento inicial del reporte.
    id_decision: str | None = None


@dataclass(frozen=True, slots=True)
class Requerimiento:
    id_requerimiento: str
    id_siniestro: str
    descripcion: str
    creado_en: datetime
    #: El evento de seguimiento que lo pidió.
    id_evento: str
    atendido_en: datetime | None = None
    id_evidencia: str | None = None

    @property
    def pendiente(self) -> bool:
        return self.atendido_en is None


def limpiar_requerimientos(descripciones: list[str] | tuple[str, ...]) -> tuple[str, ...]:
    limpias = tuple(d.strip() for d in descripciones if d and d.strip())
    if len(limpias) > MAXIMO_REQUERIMIENTOS:
        raise DatoInvalido(
            f"A lo sumo {MAXIMO_REQUERIMIENTOS} requerimientos por cambio",
            maximo=MAXIMO_REQUERIMIENTOS,
        )
    if any(len(d) > LARGO_MAXIMO_REQUERIMIENTO for d in limpias):
        raise DatoInvalido("Un requerimiento es demasiado largo", maximo=LARGO_MAXIMO_REQUERIMIENTO)
    return limpias
