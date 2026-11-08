"""Modelo de las notificaciones push (MOB-F06).

Un `Dispositivo` es la app instalada en un teléfono, identificada por el token que le da el
proveedor de push. El identificador del dispositivo se deriva del token, así que registrarlo
otra vez (la app se abre, el token no cambió) es idempotente. Si el token aparece con otro
cliente, por ejemplo porque el teléfono cambió de dueño o la app se reinstaló con otra
cuenta, pasa al cliente nuevo: un push nunca le llega a quien ya no usa ese teléfono.

Una `Notificacion` es lo que el cliente recibe por un cambio de estado de su siniestro. Se
crea una sola por evento, aunque el evento llegue dos veces, y lleva los datos para abrir el
detalle del siniestro al tocarla (criterio 2).
"""

from __future__ import annotations

import hashlib
from dataclasses import dataclass, field
from datetime import datetime
from enum import StrEnum

LARGO_MAXIMO_TOKEN = 4096


class Plataforma(StrEnum):
    ANDROID = "android"


class EstadoEntrega(StrEnum):
    #: El proveedor aceptó el push para al menos un dispositivo.
    ENVIADA = "enviada"
    #: El cliente no tiene dispositivos registrados: el estado se ve en el seguimiento (P20).
    SIN_DISPOSITIVO = "sin_dispositivo"
    #: El proveedor rechazó o no respondió para todos los dispositivos.
    FALLIDA = "fallida"


def id_de_dispositivo(token: str) -> str:
    """Identificador estable del dispositivo, sin exponer el token en rutas ni registros."""
    return "DIS-" + hashlib.sha256(token.encode()).hexdigest()[:32]


@dataclass(frozen=True, slots=True)
class Dispositivo:
    id_dispositivo: str
    id_cliente: str
    token: str
    plataforma: Plataforma
    registrado_en: datetime
    actualizado_en: datetime


@dataclass(frozen=True, slots=True)
class Notificacion:
    id_notificacion: str
    id_cliente: str
    #: El evento que la originó; una notificación por evento.
    id_evento: str
    titulo: str
    cuerpo: str
    datos: dict[str, str] = field(default_factory=dict)
    creada_en: datetime | None = None
    estado: EstadoEntrega = EstadoEntrega.SIN_DISPOSITIVO
    dispositivos: int = 0
    leida_en: datetime | None = None

    @property
    def leida(self) -> bool:
        return self.leida_en is not None


#: Qué se le dice al cliente según el estado nuevo. El canal puede usar los suyos con `datos`.
TEXTOS_POR_ESTADO = {
    "en_evaluacion": "Ya estamos evaluando tu caso.",
    "requiere_informacion": "Necesitamos un documento para seguir con tu caso.",
    "aprobado": "Tu caso fue aprobado.",
    "rechazado": "Tu caso fue revisado y no procede. Revisa el detalle.",
    "pagado": "Ya pagamos tu indemnización.",
}


def texto_del_cambio(referencia: str, estado: str) -> tuple[str, str]:
    """Título y cuerpo del push por un cambio de estado."""
    titulo = f"Tu siniestro {referencia} cambió"
    return titulo, TEXTOS_POR_ESTADO.get(estado, "Tu caso tiene novedades.")
