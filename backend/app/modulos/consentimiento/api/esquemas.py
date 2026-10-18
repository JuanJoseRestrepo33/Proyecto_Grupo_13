"""DTO del contrato v1 de consentimiento.

Mismos nombres y formas que el contrato del BFF web (`contratos/bff-web/openapi.yaml`),
más `por_vencer` y `dias_para_vencer`, que el BFF necesita para el aviso de los 30 días.
"""

from __future__ import annotations

from datetime import datetime
from enum import StrEnum

from pydantic import BaseModel, ConfigDict, Field

from ..dominio import catalogo
from ..dominio.catalogo import CodigoFinalidad, Finalidad
from ..dominio.modelo import Consentimiento, Estado, EventoConsentimiento, TipoEvento


class Canal(StrEnum):
    WEB = "web"
    MOVIL = "movil"


class _Dto(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


class EntidadDto(_Dto):
    id: str
    nombre: str


class FinalidadDto(_Dto):
    codigo: CodigoFinalidad
    nombre: str
    descripcion: str
    alcance: tuple[str, ...]
    vigencia_meses: int
    entidades_origen: tuple[EntidadDto, ...]
    version_texto: str


class SolicitudConsentimientoDto(_Dto):
    finalidad: CodigoFinalidad
    entidades_origen: list[str] = Field(min_length=1)
    version_texto: str = Field(description="Versión del texto legal que el cliente leyó y aceptó")


class ConsentimientoDto(_Dto):
    id: str
    finalidad: CodigoFinalidad
    entidades_origen: tuple[EntidadDto, ...]
    estado: Estado
    canal: str
    otorgado_en: datetime
    vence_en: datetime
    revocado_en: datetime | None
    version_texto: str
    por_vencer: bool
    dias_para_vencer: int | None


class EventoConsentimientoDto(_Dto):
    tipo: TipoEvento
    ocurrido_en: datetime
    canal: str
    version_texto: str


def _entidad_dto(id_entidad: str) -> EntidadDto:
    entidad = catalogo.entidad(id_entidad)
    nombre = entidad.nombre if entidad else id_entidad
    return EntidadDto(id=id_entidad, nombre=nombre)


def a_finalidad_dto(finalidad: Finalidad) -> FinalidadDto:
    return FinalidadDto(
        codigo=finalidad.codigo,
        nombre=finalidad.nombre,
        descripcion=finalidad.descripcion,
        alcance=finalidad.alcance,
        vigencia_meses=finalidad.vigencia_meses,
        entidades_origen=tuple(
            EntidadDto(id=e.id, nombre=e.nombre) for e in finalidad.entidades_origen
        ),
        version_texto=finalidad.version_texto,
    )


def a_consentimiento_dto(consentimiento: Consentimiento, ahora: datetime) -> ConsentimientoDto:
    return ConsentimientoDto(
        id=consentimiento.id_consentimiento,
        finalidad=consentimiento.finalidad,
        entidades_origen=tuple(_entidad_dto(i) for i in consentimiento.entidades_origen),
        estado=consentimiento.estado(ahora),
        canal=consentimiento.canal,
        otorgado_en=consentimiento.otorgado_en,
        vence_en=consentimiento.vence_en,
        revocado_en=consentimiento.revocado_en,
        version_texto=consentimiento.version_texto,
        por_vencer=consentimiento.por_vencer(ahora),
        dias_para_vencer=consentimiento.dias_para_vencer(ahora),
    )


def a_evento_dto(evento: EventoConsentimiento) -> EventoConsentimientoDto:
    return EventoConsentimientoDto(
        tipo=evento.tipo,
        ocurrido_en=evento.ocurrido_en,
        canal=evento.canal,
        version_texto=evento.version_texto,
    )
