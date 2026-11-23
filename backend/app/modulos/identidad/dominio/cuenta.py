"""Cuenta del cliente y registro con verificación del correo (MOB-F01, WEB-F13).

La cuenta es la misma en los dos canales: quien se registra en la app entra a la web con el
mismo correo y la misma contraseña (WEB-F13, criterio 1). El correo se guarda normalizado
para que `Ana@Ejemplo.co` y `ana@ejemplo.co` sean la misma cuenta.

El registro es el trámite de verificar el correo con un código de un solo uso (OTP): vale
10 minutos, admite 3 intentos y no se puede pedir otro antes de 30 segundos. El código no
se guarda: se guarda su resumen, ligado al registro.
"""

from __future__ import annotations

import hashlib
import hmac
from dataclasses import dataclass, replace
from datetime import datetime, timedelta

from .modelo import TipoDocumento

VIGENCIA_OTP = timedelta(minutes=10)
ESPERA_REENVIO = timedelta(seconds=30)
INTENTOS_OTP = 3


def normalizar_correo(correo: str) -> str:
    return correo.strip().lower()


@dataclass(frozen=True, slots=True)
class DatosDeRegistro:
    nombre_completo: str
    tipo_documento: TipoDocumento
    numero_documento: str
    correo: str
    celular: str
    contrasena: str
    version_terminos: str

    def __repr__(self) -> str:  # la contraseña nunca llega a un log, ni por accidente
        return f"DatosDeRegistro(correo={self.correo!r}, contrasena=***)"


@dataclass(frozen=True, slots=True)
class Cuenta:
    id_cliente: str
    nombre_completo: str
    tipo_documento: TipoDocumento
    numero_documento: str
    correo: str
    celular: str
    credencial: str
    version_terminos: str
    creada_en: datetime
    correo_verificado: bool = False

    def con_correo_verificado(self) -> Cuenta:
        return replace(self, correo_verificado=True)


def resumen_de_codigo(id_registro: str, codigo: str) -> str:
    """El código ligado a su registro: el mismo código en otro registro da otro resumen."""
    return hashlib.sha256(f"{id_registro}:{codigo}".encode()).hexdigest()


@dataclass(frozen=True, slots=True)
class Registro:
    id_registro: str
    id_cliente: str
    resumen_codigo: str
    expira_en: datetime
    reenvio_desde: datetime
    intentos_restantes: int = INTENTOS_OTP
    verificado: bool = False

    @classmethod
    def nuevo(cls, id_registro: str, id_cliente: str, codigo: str, ahora: datetime) -> Registro:
        return cls(
            id_registro=id_registro,
            id_cliente=id_cliente,
            resumen_codigo=resumen_de_codigo(id_registro, codigo),
            expira_en=ahora + VIGENCIA_OTP,
            reenvio_desde=ahora + ESPERA_REENVIO,
        )

    def vencido(self, ahora: datetime) -> bool:
        return ahora >= self.expira_en

    def acierta(self, codigo: str) -> bool:
        return hmac.compare_digest(self.resumen_codigo, resumen_de_codigo(self.id_registro, codigo))

    def con_intento_fallido(self) -> Registro:
        return replace(self, intentos_restantes=max(self.intentos_restantes - 1, 0))

    def como_verificado(self) -> Registro:
        return replace(self, verificado=True)

    def con_codigo_nuevo(self, codigo: str, ahora: datetime) -> Registro:
        """Un código nuevo invalida el anterior y repone los intentos."""
        return replace(
            Registro.nuevo(self.id_registro, self.id_cliente, codigo, ahora),
            verificado=self.verificado,
        )

    def segundos_para_reenviar(self, ahora: datetime) -> int:
        return max(int((self.reenvio_desde - ahora).total_seconds() + 0.999), 0)
