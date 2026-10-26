"""Errores del dominio de identidad.

Los códigos son los que el BFF móvil ya devuelve desde sus mocks
(`mobile/android/app/src/debug/.../MockSesiones.kt` y `MockRegistro.kt`), para que la app
no distinga de dónde vino el error.
"""

from __future__ import annotations

from app.compartido.errores import (
    DatoInvalido,
    DemasiadasSolicitudes,
    DependenciaNoDisponible,
    NoAutenticado,
    RecursoNoEncontrado,
    ReglaDeNegocioViolada,
)


class IdentidadYaVerificadaError(ReglaDeNegocioViolada):
    codigo = "identidad_ya_verificada"


class VerificacionEnCursoError(ReglaDeNegocioViolada):
    codigo = "verificacion_en_curso"


class ProveedorKYCNoDisponibleError(DependenciaNoDisponible):
    """El proveedor de KYC/AML falló o no respondió a tiempo.

    El núcleo nunca ve la excepción del cliente HTTP del proveedor: el adaptador la
    traduce a esta (ARQ-02, criterio 4).
    """

    codigo = "proveedor_kyc_no_disponible"


# -- registro y verificación del correo (MOB-F01, WEB-F13) ---------------------------


class CorreoYaRegistradoError(ReglaDeNegocioViolada):
    codigo = "correo_ya_registrado"


class DocumentoYaRegistradoError(ReglaDeNegocioViolada):
    codigo = "documento_ya_registrado"


class RegistroNoEncontradoError(RecursoNoEncontrado):
    codigo = "registro_no_encontrado"


class RegistroYaVerificadoError(ReglaDeNegocioViolada):
    codigo = "registro_ya_verificado"


class OtpBloqueadoError(ReglaDeNegocioViolada):
    """Se agotaron los intentos del código. Hay que pedir otro; la cuenta no se bloquea."""

    codigo = "otp_bloqueado"


class OtpVencidoError(ReglaDeNegocioViolada):
    codigo = "otp_vencido"


class OtpInvalidoError(DatoInvalido):
    """Código incorrecto. Trae `intentos_restantes` en sus detalles."""

    codigo = "otp_invalido"


class OtpReenvioPrematuroError(DemasiadasSolicitudes):
    codigo = "otp_reenvio_prematuro"


# -- sesión (WEB-F13) ----------------------------------------------------------------


class CredencialesInvalidasError(NoAutenticado):
    """Correo o contraseña incorrectos. Mismo código y mensaje exista o no el correo."""

    codigo = "credenciales_invalidas"


class TokenRefrescoInvalidoError(NoAutenticado):
    """Token de refresco vencido, revocado o ya usado. La app vuelve a pedir la contraseña."""

    codigo = "token_refresco_invalido"


class ClienteNoEncontradoError(RecursoNoEncontrado):
    codigo = "cliente_no_encontrado"
