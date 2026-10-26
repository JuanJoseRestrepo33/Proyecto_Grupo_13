"""DTO del contrato v1 de identidad.

`VerificacionIdentidadDto` tiene la misma forma que `VerificacionIdentidad` del contrato
del BFF móvil (`contratos/bff-movil/openapi.yaml`), para que el BFF la pase tal cual.
"""

from __future__ import annotations

from datetime import datetime
from enum import StrEnum

from pydantic import BaseModel, ConfigDict, Field

from ..aplicacion.cuentas import SesionAbierta
from ..dominio.cuenta import Cuenta, DatosDeRegistro, Registro
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


# -- registro y sesión (MOB-F01, WEB-F13) ---------------------------------------------
# Mismas formas que `Registro`, `Otp`, `Cliente` y `Sesion` del contrato del BFF móvil.

_CORREO = r"^[^@\s]+@[^@\s]+\.[^@\s]+$"


class SolicitudRegistroDto(_Dto):
    nombre_completo: str = Field(min_length=3, max_length=120)
    tipo_documento: TipoDocumento
    numero_documento: str = Field(pattern=r"^[A-Za-z0-9]{5,15}$")
    correo: str = Field(max_length=254, pattern=_CORREO)
    celular: str = Field(pattern=r"^3\d{9}$", description="Celular colombiano, 10 dígitos")
    contrasena: str = Field(min_length=8, max_length=64, repr=False)
    version_terminos: str = Field(min_length=1, max_length=20)

    def a_datos(self) -> DatosDeRegistro:
        return DatosDeRegistro(
            nombre_completo=self.nombre_completo,
            tipo_documento=self.tipo_documento,
            numero_documento=self.numero_documento,
            correo=self.correo,
            celular=self.celular,
            contrasena=self.contrasena,
            version_terminos=self.version_terminos,
        )


class SolicitudOtpDto(_Dto):
    codigo: str = Field(pattern=r"^\d{6}$")


class OtpDto(_Dto):
    expira_en: datetime
    intentos_restantes: int
    reenvio_disponible_en: datetime


class RegistroDto(_Dto):
    id: str
    id_cliente: str
    estado_identidad: EstadoIdentidad
    otp: OtpDto


class ClienteDto(_Dto):
    id: str
    nombre_completo: str
    correo: str
    estado_identidad: EstadoIdentidad


class SesionDto(_Dto):
    token_acceso: str
    token_refresco: str
    expira_en: datetime
    cliente: ClienteDto


def a_otp_dto(registro: Registro) -> OtpDto:
    return OtpDto(
        expira_en=registro.expira_en,
        intentos_restantes=registro.intentos_restantes,
        reenvio_disponible_en=registro.reenvio_desde,
    )


def a_registro_dto(registro: Registro, estado: EstadoIdentidad) -> RegistroDto:
    return RegistroDto(
        id=registro.id_registro,
        id_cliente=registro.id_cliente,
        estado_identidad=estado,
        otp=a_otp_dto(registro),
    )


def a_cliente_dto(cuenta: Cuenta, estado: EstadoIdentidad) -> ClienteDto:
    return ClienteDto(
        id=cuenta.id_cliente,
        nombre_completo=cuenta.nombre_completo,
        correo=cuenta.correo,
        estado_identidad=estado,
    )


def a_sesion_dto(sesion: SesionAbierta) -> SesionDto:
    return SesionDto(
        token_acceso=sesion.acceso.token,
        token_refresco=sesion.refresco,
        expira_en=sesion.acceso.expira_en,
        cliente=a_cliente_dto(sesion.cuenta, sesion.estado_identidad),
    )
