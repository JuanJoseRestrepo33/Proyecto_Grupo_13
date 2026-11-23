"""Modelo del consentimiento Open Finance.

Un consentimiento es una autorización del cliente para una finalidad, sobre unas
entidades, por una vigencia. Nunca se edita: cada cambio de estado produce una copia
nueva con un evento más en su histórico, que es lo que el cliente ve en orden cronológico
(criterio 8) y lo que el log de auditoría recibe (criterio 6).
"""

from __future__ import annotations

import calendar
from dataclasses import dataclass, field, replace
from datetime import datetime, timedelta
from enum import StrEnum

from .catalogo import CodigoFinalidad
from .errores import ConsentimientoNoVigenteError, ConsentimientoRevocadoError

#: Con cuántos días de anticipación se avisa que un consentimiento va a vencer (criterio 7).
DIAS_AVISO_VENCIMIENTO = 30


class Canal(StrEnum):
    """Por dónde llegó un cambio. El consentimiento es del cliente, no del canal: uno otorgado
    en la app rige en la web y viceversa (MOB-F02). `sistema` lo usa solo el núcleo, para el
    vencimiento; un cliente nunca actúa como `sistema`."""

    WEB = "web"
    MOVIL = "movil"
    SISTEMA = "sistema"


CANAL_SISTEMA = Canal.SISTEMA
#: Canales por los que un cliente puede otorgar, revocar o renovar.
CANALES_DEL_CLIENTE = frozenset({Canal.WEB, Canal.MOVIL})


class Estado(StrEnum):
    VIGENTE = "vigente"
    REVOCADO = "revocado"
    VENCIDO = "vencido"


class TipoEvento(StrEnum):
    OTORGADO = "otorgado"
    RENOVADO = "renovado"
    REVOCADO = "revocado"
    VENCIDO = "vencido"


def sumar_meses(fecha: datetime, meses: int) -> datetime:
    """Suma meses calendario. Si el día no existe en el mes destino, usa el último."""
    indice = fecha.month - 1 + meses
    anio = fecha.year + indice // 12
    mes = indice % 12 + 1
    dia = min(fecha.day, calendar.monthrange(anio, mes)[1])
    return fecha.replace(year=anio, month=mes, day=dia)


@dataclass(frozen=True, slots=True)
class EventoConsentimiento:
    tipo: TipoEvento
    ocurrido_en: datetime
    canal: str
    version_texto: str


@dataclass(frozen=True, slots=True)
class EstadoDeConsentimiento:
    """Respuesta a "¿este cliente autorizó esta finalidad?". Es lo que cotización consulta."""

    vigente: bool
    id_consentimiento: str | None = None


@dataclass(frozen=True, slots=True)
class Consentimiento:
    id_consentimiento: str
    id_cliente: str
    finalidad: CodigoFinalidad
    entidades_origen: tuple[str, ...]
    canal: str
    otorgado_en: datetime
    vence_en: datetime
    version_texto: str
    revocado_en: datetime | None = None
    eventos: tuple[EventoConsentimiento, ...] = field(default_factory=tuple)

    @classmethod
    def otorgar(
        cls,
        *,
        id_consentimiento: str,
        id_cliente: str,
        finalidad: CodigoFinalidad,
        entidades_origen: tuple[str, ...],
        canal: str,
        ahora: datetime,
        vigencia_meses: int,
        version_texto: str,
    ) -> Consentimiento:
        return cls(
            id_consentimiento=id_consentimiento,
            id_cliente=id_cliente,
            finalidad=finalidad,
            entidades_origen=entidades_origen,
            canal=canal,
            otorgado_en=ahora,
            vence_en=sumar_meses(ahora, vigencia_meses),
            version_texto=version_texto,
            eventos=(EventoConsentimiento(TipoEvento.OTORGADO, ahora, canal, version_texto),),
        )

    # --- estado -------------------------------------------------------------------------

    def estado(self, ahora: datetime) -> Estado:
        if self.revocado_en is not None:
            return Estado.REVOCADO
        if ahora >= self.vence_en:
            return Estado.VENCIDO
        return Estado.VIGENTE

    def vigente(self, ahora: datetime) -> bool:
        return self.estado(ahora) is Estado.VIGENTE

    def dias_para_vencer(self, ahora: datetime) -> int | None:
        if not self.vigente(ahora):
            return None
        return (self.vence_en - ahora).days

    def por_vencer(self, ahora: datetime) -> bool:
        return self.vigente(ahora) and self.vence_en - ahora <= timedelta(
            days=DIAS_AVISO_VENCIMIENTO
        )

    @property
    def ultimo_evento(self) -> EventoConsentimiento:
        return self.eventos[-1]

    # --- transiciones -------------------------------------------------------------------

    def revocar(self, ahora: datetime, canal: str) -> Consentimiento:
        if not self.vigente(ahora):
            raise ConsentimientoNoVigenteError(
                "Solo se revoca un consentimiento vigente",
                estado=str(self.estado(ahora)),
            )
        evento = EventoConsentimiento(TipoEvento.REVOCADO, ahora, canal, self.version_texto)
        return replace(self, revocado_en=ahora, eventos=(*self.eventos, evento))

    def renovar(
        self, ahora: datetime, canal: str, vigencia_meses: int, version_texto: str
    ) -> Consentimiento:
        """Extiende la vigencia sin repetir el flujo completo (criterio 7).

        Un consentimiento vencido se puede renovar; uno revocado no: revocar fue una
        decisión del cliente y volver atrás exige otorgarlo de nuevo, con el flujo completo.
        """
        if self.revocado_en is not None:
            raise ConsentimientoRevocadoError("Un consentimiento revocado se otorga de nuevo")
        base = self.con_vencimiento_registrado(ahora)
        evento = EventoConsentimiento(TipoEvento.RENOVADO, ahora, canal, version_texto)
        return replace(
            base,
            vence_en=sumar_meses(ahora, vigencia_meses),
            version_texto=version_texto,
            eventos=(*base.eventos, evento),
        )

    def con_vencimiento_registrado(self, ahora: datetime) -> Consentimiento:
        """Si ya venció y el histórico no lo dice, agrega el evento `vencido`.

        El vencimiento no lo provoca nadie: ocurre solo. Se materializa en el histórico la
        primera vez que alguien mira el consentimiento después de la fecha.
        """
        if (
            self.estado(ahora) is not Estado.VENCIDO
            or self.ultimo_evento.tipo is TipoEvento.VENCIDO
        ):
            return self
        evento = EventoConsentimiento(
            TipoEvento.VENCIDO, self.vence_en, CANAL_SISTEMA, self.version_texto
        )
        return replace(self, eventos=(*self.eventos, evento))
