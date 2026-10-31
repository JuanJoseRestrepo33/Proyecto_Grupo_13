"""Rutas HTTP del módulo de identidad, bajo la fachada /v1.

El núcleo no autentica las solicitudes del BFF: el BFF resuelve la sesión y pasa el cliente
en la ruta y el canal en la cabecera `X-Canal`. Lo que sí hace el núcleo es abrir sesiones:
registra cuentas, verifica el correo y emite los tokens que el BFF después valida.
"""

from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends, Header, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from ..aplicacion.cuentas import ServicioDeCuentas
from ..aplicacion.servicio import ServicioDeVerificacionDeIdentidad
from ..dominio.sesion import SesionNoVigenteError
from .dependencias import obtener_servicio, obtener_servicio_de_cuentas
from .esquemas import (
    Canal,
    ClienteDto,
    EstadoIdentidadDto,
    OtpDto,
    RegistroDto,
    SesionDto,
    SolicitudOtpDto,
    SolicitudRegistroDto,
    SolicitudRenovacionDto,
    SolicitudSesionDto,
    SolicitudVerificacionDto,
    VerificacionIdentidadDto,
    a_cliente_dto,
    a_otp_dto,
    a_registro_dto,
    a_sesion_dto,
    a_verificacion_dto,
)

router = APIRouter(prefix="/identidad", tags=["identidad"])

Servicio = Annotated[ServicioDeVerificacionDeIdentidad, Depends(obtener_servicio)]
Cuentas = Annotated[ServicioDeCuentas, Depends(obtener_servicio_de_cuentas)]
Portador = Annotated[HTTPAuthorizationCredentials | None, Depends(HTTPBearer(auto_error=False))]
CanalDep = Annotated[Canal, Header(alias="X-Canal", description="Canal que origina la acción")]

RUTA_CLIENTE = "/clientes/{id_cliente}"


@router.post(
    RUTA_CLIENTE + "/verificaciones",
    response_model=VerificacionIdentidadDto,
    status_code=status.HTTP_202_ACCEPTED,
    summary="Envía al proveedor KYC/AML los datos dados o, sin cuerpo, los de la cuenta",
)
def solicitar(
    id_cliente: str,
    servicio: Servicio,
    cuentas: Cuentas,
    solicitud: SolicitudVerificacionDto | None = None,
    canal: CanalDep = Canal.MOVIL,
) -> VerificacionIdentidadDto:
    """Sin cuerpo, usa los datos de la cuenta: así el documento no pasa por el BFF.

    La respuesta puede ser inmediata (`aprobada`, `rechazada`, `en_revision`) o
    `en_proceso`; en ese caso la app consulta la última verificación en lugar de esperar.
    409 si la identidad ya está verificada o hay una verificación en curso; 404
    `cliente_no_encontrado` sin cuerpo y sin cuenta; 503 si el proveedor no responde."""
    datos = solicitud.a_datos() if solicitud else cuentas.datos_de_identidad(id_cliente)
    return a_verificacion_dto(servicio.solicitar(id_cliente, datos, str(canal)))


@router.get(
    RUTA_CLIENTE + "/verificaciones/ultima",
    response_model=VerificacionIdentidadDto,
    summary="Estado de la última verificación de identidad",
)
def consultar(id_cliente: str, servicio: Servicio) -> VerificacionIdentidadDto:
    """Si estaba `en_proceso`, pregunta al proveedor antes de responder. 404 si el cliente
    nunca solicitó una."""
    return a_verificacion_dto(servicio.consultar(id_cliente))


@router.get(
    RUTA_CLIENTE + "/estado",
    response_model=EstadoIdentidadDto,
    summary="Estado de identidad de la cuenta",
)
def estado(id_cliente: str, servicio: Servicio) -> EstadoIdentidadDto:
    """`pendiente_verificacion`, `en_revision`, `verificada` o `rechazada`. Es lo que el BFF
    pone en `/sesion` y lo que decide a qué funciones puede entrar el cliente."""
    return EstadoIdentidadDto(
        id_cliente=id_cliente, estado_identidad=servicio.estado_identidad(id_cliente)
    )


# -- registro y verificación del correo (MOB-F01, WEB-F13) -----------------------------


@router.post(
    "/registros",
    response_model=RegistroDto,
    status_code=status.HTTP_201_CREATED,
    summary="Crea la cuenta y envía el código de verificación al correo",
)
def registrar(
    solicitud: SolicitudRegistroDto, cuentas: Cuentas, canal: CanalDep = Canal.MOVIL
) -> RegistroDto:
    """La cuenta queda `pendiente_verificacion`, la contraseña se guarda con hash y sal y se
    publica `ClientRegistered`. 409 `correo_ya_registrado` o `documento_ya_registrado`; 422
    `contrasena_debil` si no tiene una letra y un número."""
    registro, cuenta = cuentas.registrar(solicitud.a_datos(), str(canal))
    return a_registro_dto(registro, cuentas.estado_identidad(cuenta.id_cliente))


@router.post(
    "/registros/{id_registro}/otp/verificacion",
    response_model=SesionDto,
    summary="Verifica el código enviado al correo y abre la primera sesión",
)
def verificar_correo(
    id_registro: str, solicitud: SolicitudOtpDto, cuentas: Cuentas, canal: CanalDep = Canal.MOVIL
) -> SesionDto:
    """3 intentos por código: 422 `otp_invalido` con `intentos_restantes` en los detalles y,
    al tercer fallo, 409 `otp_bloqueado`. 409 `otp_vencido` o `registro_ya_verificado`."""
    return a_sesion_dto(cuentas.verificar_correo(id_registro, solicitud.codigo, str(canal)))


@router.post(
    "/registros/{id_registro}/otp/reenvio",
    response_model=OtpDto,
    status_code=status.HTTP_202_ACCEPTED,
    summary="Envía un código nuevo e invalida el anterior",
)
def reenviar_codigo(id_registro: str, cuentas: Cuentas) -> OtpDto:
    """429 `otp_reenvio_prematuro` con `Retry-After` si se pidió otro hace menos de 30 s."""
    return a_otp_dto(cuentas.reenviar_codigo(id_registro))


# -- sesión (WEB-F13) ------------------------------------------------------------------


@router.post("/sesiones", response_model=SesionDto, summary="Ingreso con correo y contraseña")
def iniciar_sesion(
    solicitud: SolicitudSesionDto, cuentas: Cuentas, canal: CanalDep = Canal.MOVIL
) -> SesionDto:
    """La misma cuenta en los dos canales; el canal fija la vigencia del token de acceso
    (60 minutos en la web, 15 en la app). 401 `credenciales_invalidas`, igual exista o no
    el correo."""
    sesion = cuentas.iniciar_sesion(solicitud.correo, solicitud.contrasena, str(canal))
    return a_sesion_dto(sesion)


@router.post(
    "/sesiones/renovacion",
    response_model=SesionDto,
    summary="Renueva la sesión con el token de refresco",
)
def renovar_sesion(solicitud: SolicitudRenovacionDto, cuentas: Cuentas) -> SesionDto:
    """Entrega un token de refresco nuevo e invalida el anterior. 401
    `token_refresco_invalido` si venció, ya se usó o no existe."""
    return a_sesion_dto(cuentas.renovar_sesion(solicitud.token_refresco))


@router.get(RUTA_CLIENTE, response_model=ClienteDto, summary="Datos de la cuenta del cliente")
def cliente(id_cliente: str, cuentas: Cuentas) -> ClienteDto:
    """Lo que el BFF pone en `/sesion`. 404 `cliente_no_encontrado`."""
    return a_cliente_dto(cuentas.cliente(id_cliente), cuentas.estado_identidad(id_cliente))


@router.get("/sesion", response_model=ClienteDto, summary="Cliente de un token de acceso")
def sesion(portador: Portador, cuentas: Cuentas) -> ClienteDto:
    """Valida el token de `Authorization: Bearer` con la llave del núcleo. El BFF valida por
    su cuenta con la pública; esta ruta sirve para diagnosticar un token. 401
    `sesion_no_vigente`."""
    if portador is None:
        raise SesionNoVigenteError("Falta el token de acceso")
    cuenta = cuentas.cliente_de_sesion(portador.credentials)
    return a_cliente_dto(cuenta, cuentas.estado_identidad(cuenta.id_cliente))
