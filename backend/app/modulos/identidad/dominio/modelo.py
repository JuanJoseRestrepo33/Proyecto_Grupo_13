"""Modelo de la verificación de identidad (KYC/AML).

Una verificación es un intento del cliente de demostrar quién es ante el proveedor. El
proveedor responde `aprobada`, `rechazada` (con un motivo en lenguaje del dominio, nunca
el código crudo del proveedor), `en_revision` (revisión manual) o `en_proceso` (todavía no
hay respuesta y la app vuelve a consultar). Del resultado se deriva el estado de identidad
de la cuenta, que es lo que decide a qué funciones puede entrar el cliente.
"""

from __future__ import annotations

from dataclasses import dataclass, replace
from datetime import datetime
from enum import StrEnum


class TipoDocumento(StrEnum):
    CEDULA_CIUDADANIA = "cedula_ciudadania"
    CEDULA_EXTRANJERIA = "cedula_extranjeria"
    PASAPORTE = "pasaporte"


class EstadoVerificacion(StrEnum):
    EN_PROCESO = "en_proceso"
    APROBADA = "aprobada"
    RECHAZADA = "rechazada"
    EN_REVISION = "en_revision"


class MotivoRechazo(StrEnum):
    """Motivos en lenguaje claro. La app los explica; nunca muestra jerga del proveedor."""

    DATOS_NO_COINCIDEN = "datos_no_coinciden"
    DOCUMENTO_NO_VALIDO = "documento_no_valido"
    NO_SUPERADA = "no_superada"


class EstadoIdentidad(StrEnum):
    PENDIENTE_VERIFICACION = "pendiente_verificacion"
    EN_REVISION = "en_revision"
    VERIFICADA = "verificada"
    RECHAZADA = "rechazada"


#: Estados en los que no se admite otra solicitud: hay una verificación andando.
EN_CURSO = frozenset({EstadoVerificacion.EN_PROCESO, EstadoVerificacion.EN_REVISION})


@dataclass(frozen=True, slots=True)
class DatosDeIdentidad:
    """Lo que el proveedor necesita para verificar. Sin documento ni selfie en el Sprint 1."""

    nombre_completo: str
    tipo_documento: TipoDocumento
    numero_documento: str


@dataclass(frozen=True, slots=True)
class ResultadoKYC:
    """Respuesta del proveedor ya traducida al dominio."""

    estado: EstadoVerificacion
    proveedor: str
    referencia_proveedor: str | None = None
    motivo: MotivoRechazo | None = None
    reintento_permitido: bool | None = None

    def __post_init__(self) -> None:
        if self.estado is EstadoVerificacion.RECHAZADA and self.motivo is None:
            raise ValueError("un rechazo siempre trae motivo: la app tiene que explicarlo")
        if self.estado is not EstadoVerificacion.RECHAZADA and self.motivo is not None:
            raise ValueError("solo un rechazo lleva motivo")


@dataclass(frozen=True, slots=True)
class VerificacionIdentidad:
    id_verificacion: str
    id_cliente: str
    datos: DatosDeIdentidad
    canal: str
    solicitada_en: datetime
    actualizada_en: datetime
    estado: EstadoVerificacion
    proveedor: str
    referencia_proveedor: str | None = None
    motivo: MotivoRechazo | None = None
    reintento_permitido: bool | None = None

    @property
    def en_curso(self) -> bool:
        return self.estado in EN_CURSO

    @property
    def estado_identidad(self) -> EstadoIdentidad:
        """Qué significa esta verificación para la cuenta del cliente."""
        return {
            EstadoVerificacion.APROBADA: EstadoIdentidad.VERIFICADA,
            EstadoVerificacion.RECHAZADA: EstadoIdentidad.RECHAZADA,
            EstadoVerificacion.EN_REVISION: EstadoIdentidad.EN_REVISION,
            EstadoVerificacion.EN_PROCESO: EstadoIdentidad.PENDIENTE_VERIFICACION,
        }[self.estado]

    def con_resultado(self, resultado: ResultadoKYC, ahora: datetime) -> VerificacionIdentidad:
        return replace(
            self,
            estado=resultado.estado,
            proveedor=resultado.proveedor,
            referencia_proveedor=resultado.referencia_proveedor,
            motivo=resultado.motivo,
            reintento_permitido=resultado.reintento_permitido,
            actualizada_en=ahora,
        )
