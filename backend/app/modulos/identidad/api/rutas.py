"""Rutas HTTP del módulo de identidad, bajo la fachada /v1.

El núcleo no autentica: el BFF móvil resuelve la sesión y pasa el cliente en la ruta y el
canal en la cabecera `X-Canal`. El BFF expone esto como `/identidad/verificacion`.
"""

from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends, Header, status

from ..aplicacion.servicio import ServicioDeVerificacionDeIdentidad
from .dependencias import obtener_servicio
from .esquemas import (
    Canal,
    EstadoIdentidadDto,
    SolicitudVerificacionDto,
    VerificacionIdentidadDto,
    a_verificacion_dto,
)

router = APIRouter(prefix="/identidad", tags=["identidad"])

Servicio = Annotated[ServicioDeVerificacionDeIdentidad, Depends(obtener_servicio)]
CanalDep = Annotated[Canal, Header(alias="X-Canal", description="Canal que origina la acción")]

RUTA_CLIENTE = "/clientes/{id_cliente}"


@router.post(
    RUTA_CLIENTE + "/verificaciones",
    response_model=VerificacionIdentidadDto,
    status_code=status.HTTP_202_ACCEPTED,
    summary="Envía los datos del registro al proveedor KYC/AML",
)
def solicitar(
    id_cliente: str,
    solicitud: SolicitudVerificacionDto,
    servicio: Servicio,
    canal: CanalDep = Canal.MOVIL,
) -> VerificacionIdentidadDto:
    """La respuesta puede ser inmediata (`aprobada`, `rechazada`, `en_revision`) o
    `en_proceso`; en ese caso la app consulta la última verificación en lugar de esperar.
    409 si la identidad ya está verificada o hay una verificación en curso; 503 si el
    proveedor no responde."""
    verificacion = servicio.solicitar(id_cliente, solicitud.a_datos(), str(canal))
    return a_verificacion_dto(verificacion)


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
