"""DTO del contrato v1 de identidad.

`VerificacionIdentidadDto` tiene la misma forma que `VerificacionIdentidad` del contrato
del BFF móvil (`contratos/bff-movil/openapi.yaml`), para que el BFF la pase tal cual.
"""

from __future__ import annotations

from datetime import datetime
from enum import StrEnum

from pydantic import BaseModel, ConfigDict, Field

from ..dominio.modelo import (
    DatosDeIdentidad,
    EstadoIdentidad,
    EstadoVerificacion,
    MotivoRechazo,
    TipoDocumento,
    VerificacionIdentidad,
)


class Canal(StrEnum):
    MOVIL = "movil"
    WEB = "web"


class _Dto(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


class SolicitudVerificacionDto(_Dto):
    nombre_completo: str = Field(min_length=3, max_length=120)
    tipo_documento: TipoDocumento
    numero_documento: str = Field(pattern=r"^[A-Za-z0-9]{5,15}$")

    def a_datos(self) -> DatosDeIdentidad:
        return DatosDeIdentidad(
            nombre_completo=self.nombre_completo,
            tipo_documento=self.tipo_documento,
            numero_documento=self.numero_documento,
        )


class VerificacionIdentidadDto(_Dto):
    id: str
    estado: EstadoVerificacion
    motivo: MotivoRechazo | None = None
    reintento_permitido: bool | None = None
    actualizada_en: datetime
    solicitada_en: datetime
    proveedor: str


class EstadoIdentidadDto(_Dto):
    id_cliente: str
    estado_identidad: EstadoIdentidad


def a_verificacion_dto(verificacion: VerificacionIdentidad) -> VerificacionIdentidadDto:
    return VerificacionIdentidadDto(
        id=verificacion.id_verificacion,
        estado=verificacion.estado,
        motivo=verificacion.motivo,
        reintento_permitido=verificacion.reintento_permitido,
        actualizada_en=verificacion.actualizada_en,
        solicitada_en=verificacion.solicitada_en,
        proveedor=verificacion.proveedor,
    )
