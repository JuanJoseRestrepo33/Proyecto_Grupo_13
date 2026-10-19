"""Modelo del log de auditoría.

Un `EventoAuditable` es lo que un módulo pide registrar. Un `Registro` es ese evento ya
encadenado: con número de secuencia, hash del eslabón anterior y hash propio. Un registro
no se modifica nunca; por eso todo aquí es inmutable.
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass, field
from datetime import datetime
from typing import Literal

Motivo = Literal["hash_no_coincide", "hash_anterior_no_coincide", "secuencia_discontinua"]


@dataclass(frozen=True)
class EventoAuditable:
    """Lo que queda en el log de una decisión o de un evento de consentimiento.

    `tipo` distingue decisiones de cotización y de suscripción de los eventos de
    consentimiento. `version_texto_legal` solo aplica a estos últimos: es la versión del
    texto que el cliente aceptó (criterio 6).
    """

    tipo: str
    actor: str
    canal: str
    ocurrido_en: datetime
    version_regla: str | None = None
    variables_entrada: Mapping[str, object] = field(default_factory=dict)
    resultado: Mapping[str, object] = field(default_factory=dict)
    referencias: Mapping[str, str] = field(default_factory=dict)
    version_texto_legal: str | None = None

    def __post_init__(self) -> None:
        if self.ocurrido_en.tzinfo is None:
            raise ValueError("ocurrido_en debe tener zona horaria; el log se guarda en UTC")


@dataclass(frozen=True)
class Registro:
    """Un eslabón de la cadena."""

    secuencia: int
    id_decision: str
    evento: EventoAuditable
    hash_anterior: str
    hash: str


@dataclass(frozen=True)
class EslabonRoto:
    secuencia: int
    id_decision: str
    motivo: Motivo


@dataclass(frozen=True)
class InformeDeIntegridad:
    total: int
    eslabones_rotos: tuple[EslabonRoto, ...]

    @property
    def conforme(self) -> bool:
        return not self.eslabones_rotos
