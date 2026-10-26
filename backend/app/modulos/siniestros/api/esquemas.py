"""DTO del contrato v1 de siniestros.

El BFF los traduce a los contratos de cada canal. Dentro de `v1` solo se agregan campos
opcionales.
"""

from __future__ import annotations

from datetime import date, datetime
from enum import StrEnum

from pydantic import BaseModel, ConfigDict, Field

from ..dominio.evidencias import EvidenciaDelSiniestro
from ..dominio.modelo import (
    DatosDeReporte,
    EstadoSiniestro,
    SiguientePaso,
    Siniestro,
    TipoEvento,
)


class Canal(StrEnum):
    WEB = "web"
    MOVIL = "movil"


class _Dto(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


class SolicitudReporteDto(_Dto):
    numero_poliza: str = Field(min_length=1, max_length=64)
    tipo_evento: TipoEvento
    ocurrido_el: date = Field(description="Día del evento; no puede ser futuro")
    descripcion: str = Field(min_length=10, max_length=2000)
    monto_estimado: int | None = Field(default=None, ge=0)

    def a_datos(self) -> DatosDeReporte:
        return DatosDeReporte(
            numero_poliza=self.numero_poliza,
            tipo_evento=self.tipo_evento,
            ocurrido_el=self.ocurrido_el,
            descripcion=self.descripcion.strip(),
            monto_estimado=self.monto_estimado,
        )


class SiniestroDto(_Dto):
    id: str
    referencia: str
    numero_poliza: str
    tipo_evento: TipoEvento
    ocurrido_el: date
    descripcion: str
    monto_estimado: int | None
    estado: EstadoSiniestro
    siguiente_paso: SiguientePaso
    canal: str
    reportado_en: datetime


def a_siniestro_dto(siniestro: Siniestro) -> SiniestroDto:
    datos = siniestro.datos
    return SiniestroDto(
        id=siniestro.id_siniestro,
        referencia=siniestro.referencia,
        numero_poliza=datos.numero_poliza,
        tipo_evento=datos.tipo_evento,
        ocurrido_el=datos.ocurrido_el,
        descripcion=datos.descripcion,
        monto_estimado=datos.monto_estimado,
        estado=siniestro.estado,
        siguiente_paso=siniestro.siguiente_paso,
        canal=siniestro.canal,
        reportado_en=siniestro.reportado_en,
    )


class EvidenciaDto(_Dto):
    id: str
    tipo_contenido: str
    tamano: int
    nombre_original: str | None
    canal: str
    cargada_en: datetime
    tomada_en: datetime | None = None
    latitud: float | None = None
    longitud: float | None = None
    lugar: str | None = None


def a_evidencia_dto(evidencia: EvidenciaDelSiniestro) -> EvidenciaDto:
    return EvidenciaDto(
        id=evidencia.id_evidencia,
        tipo_contenido=evidencia.tipo_contenido,
        tamano=evidencia.tamano,
        nombre_original=evidencia.nombre_original,
        canal=evidencia.canal,
        cargada_en=evidencia.cargada_en,
        tomada_en=evidencia.captura.tomada_en,
        latitud=evidencia.captura.latitud,
        longitud=evidencia.captura.longitud,
        lugar=evidencia.captura.lugar,
    )
