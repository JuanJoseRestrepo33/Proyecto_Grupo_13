"""Registro y sesión del canal móvil (MOB-F01, WEB-F13), según `contratos/bff-movil`.

Las formas de la app son casi las del núcleo: el BFF las traduce campo por campo para que
un cambio interno del núcleo no le llegue a la app. La sesión de la app sí se renueva: el
token de refresco viaja cifrado con la biometría y renovar entrega otro (rotación).
"""

from __future__ import annotations

from typing import Any, Literal

from fastapi import APIRouter, status
from pydantic import BaseModel, Field

from app.comun.errores import ErrorBff
from app.comun.nucleo import segmento
from app.comun.sesion import sesion_no_vigente

from .dependencias import CANAL, DESAFIO, ClienteMovil, Nucleo

router = APIRouter()

RUTA_NUCLEO = "/v1/identidad"
REGISTRO_NO_ENCONTRADO = "registro_no_encontrado"
_CORREO = r"^[^@\s]+@[^@\s]+\.[^@\s]+$"


class SolicitudRegistro(BaseModel):
    nombre_completo: str = Field(min_length=3, max_length=120)
    tipo_documento: Literal["cedula_ciudadania", "cedula_extranjeria", "pasaporte"]
    numero_documento: str = Field(pattern=r"^[A-Za-z0-9]{5,15}$")
    correo: str = Field(max_length=254, pattern=_CORREO)
    celular: str = Field(pattern=r"^3\d{9}$")
    contrasena: str = Field(min_length=8, max_length=64, repr=False)
    version_terminos: str = Field(min_length=1, max_length=20)


class SolicitudOtp(BaseModel):
    codigo: str = Field(pattern=r"^\d{6}$")


class Credenciales(BaseModel):
    correo: str = Field(max_length=254, pattern=_CORREO)
    contrasena: str = Field(min_length=1, max_length=128, repr=False)


class SolicitudRenovacion(BaseModel):
    token_refresco: str = Field(min_length=1, max_length=256, repr=False)


def a_otp(otp: dict[str, Any]) -> dict[str, object]:
    return {
        "expira_en": otp["expira_en"],
        "intentos_restantes": otp["intentos_restantes"],
        "reenvio_disponible_en": otp["reenvio_disponible_en"],
    }


def a_cliente(cliente: dict[str, Any]) -> dict[str, object]:
    return {
        "id": cliente["id"],
        "nombre_completo": cliente["nombre_completo"],
        "correo": cliente["correo"],
        "estado_identidad": cliente["estado_identidad"],
    }


def a_sesion(sesion: dict[str, Any]) -> dict[str, object]:
    return {
        "token_acceso": sesion["token_acceso"],
        "token_refresco": sesion["token_refresco"],
        "expira_en": sesion["expira_en"],
        "cliente": a_cliente(sesion["cliente"]),
    }


def _con_desafio(error: ErrorBff) -> ErrorBff:
    """Un 401 del núcleo llega a la app con `WWW-Authenticate`, como los del propio BFF."""
    if error.estado == status.HTTP_401_UNAUTHORIZED:
        error.encabezados = {**error.encabezados, **DESAFIO}
    return error


# -- registro -------------------------------------------------------------------------


@router.post(
    "/registros",
    status_code=status.HTTP_201_CREATED,
    tags=["registro"],
    summary="Crea la cuenta y envía el código de verificación al correo",
)
def registrar(solicitud: SolicitudRegistro, nucleo: Nucleo) -> dict[str, object]:
    registro = nucleo.post(f"{RUTA_NUCLEO}/registros", canal=CANAL, json=solicitud.model_dump())
    return {
        "id": registro["id"],
        "estado_identidad": registro["estado_identidad"],
        "otp": a_otp(registro["otp"]),
    }


@router.post(
    "/registros/{id_registro}/otp/verificacion",
    tags=["registro"],
    summary="Verifica el código enviado al correo y abre la primera sesión",
)
def verificar_otp(id_registro: str, solicitud: SolicitudOtp, nucleo: Nucleo) -> dict[str, object]:
    """422 `otp_invalido` trae `intentos_restantes`, como pide `ErrorOtp`."""
    id_registro = segmento(id_registro, REGISTRO_NO_ENCONTRADO)
    try:
        sesion = nucleo.post(
            f"{RUTA_NUCLEO}/registros/{id_registro}/otp/verificacion",
            canal=CANAL,
            json={"codigo": solicitud.codigo},
        )
    except ErrorBff as error:
        if "intentos_restantes" in error.detalles:
            error.extra["intentos_restantes"] = error.detalles["intentos_restantes"]
        raise
    return a_sesion(sesion)


@router.post(
    "/registros/{id_registro}/otp/reenvio",
    status_code=status.HTTP_202_ACCEPTED,
    tags=["registro"],
    summary="Envía un código nuevo e invalida el anterior",
)
def reenviar_otp(id_registro: str, nucleo: Nucleo) -> dict[str, object]:
    """429 `otp_reenvio_prematuro` con `Retry-After`, que llega del núcleo tal cual."""
    id_registro = segmento(id_registro, REGISTRO_NO_ENCONTRADO)
    return a_otp(nucleo.post(f"{RUTA_NUCLEO}/registros/{id_registro}/otp/reenvio"))


# -- sesión ---------------------------------------------------------------------------


@router.post("/sesiones", tags=["sesiones"], summary="Ingreso con correo y contraseña")
def iniciar_sesion(credenciales: Credenciales, nucleo: Nucleo) -> dict[str, object]:
    """También es el respaldo de la biometría. 401 `credenciales_invalidas`."""
    try:
        sesion = nucleo.post(
            f"{RUTA_NUCLEO}/sesiones",
            canal=CANAL,
            json={"correo": credenciales.correo, "contrasena": credenciales.contrasena},
        )
    except ErrorBff as error:
        raise _con_desafio(error) from None
    return a_sesion(sesion)


@router.post(
    "/sesiones/renovacion",
    tags=["sesiones"],
    summary="Renueva la sesión con el token de refresco (ingreso biométrico)",
)
def renovar_sesion(solicitud: SolicitudRenovacion, nucleo: Nucleo) -> dict[str, object]:
    """401 `token_refresco_invalido`: la app borra el token guardado y pide la contraseña."""
    try:
        sesion = nucleo.post(
            f"{RUTA_NUCLEO}/sesiones/renovacion",
            json={"token_refresco": solicitud.token_refresco},
        )
    except ErrorBff as error:
        raise _con_desafio(error) from None
    return a_sesion(sesion)


@router.get("/sesion", tags=["sesiones"], summary="Cliente autenticado")
def obtener_sesion(cliente: ClienteMovil, nucleo: Nucleo) -> dict[str, object]:
    """Si la cuenta ya no existe en el núcleo, la sesión no es vigente: la app renueva o
    vuelve al inicio de sesión."""
    try:
        cuenta = nucleo.get(f"{RUTA_NUCLEO}/clientes/{cliente.id_cliente}")
    except ErrorBff as error:
        if error.estado == status.HTTP_404_NOT_FOUND:
            raise sesion_no_vigente(DESAFIO) from error
        raise
    return a_cliente(cuenta)
