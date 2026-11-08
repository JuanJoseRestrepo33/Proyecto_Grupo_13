"""Rutas HTTP del módulo de notificaciones (MOB-F06), bajo la fachada /v1.

    POST   /notificaciones/clientes/{c}/dispositivos                      registrar (crit. 4)
    DELETE /notificaciones/clientes/{c}/dispositivos/{id}                 dar de baja
    GET    /notificaciones/clientes/{c}/notificaciones                    las del cliente
    POST   /notificaciones/clientes/{c}/notificaciones/{id}/lectura       marcar leída

El núcleo no autentica: el BFF resuelve la sesión y pasa el cliente en la ruta. El token del
dispositivo viaja en el cuerpo y nunca vuelve en una respuesta.
"""

from __future__ import annotations

from datetime import datetime
from typing import Annotated

from fastapi import APIRouter, Depends, Query, Response, status
from pydantic import BaseModel, ConfigDict, Field

from ..aplicacion.servicio import ServicioDeNotificaciones
from ..dominio.modelo import (
    LARGO_MAXIMO_TOKEN,
    Dispositivo,
    EstadoEntrega,
    Notificacion,
    Plataforma,
)
from .dependencias import obtener_servicio

router = APIRouter(prefix="/notificaciones", tags=["notificaciones"])

Servicio = Annotated[ServicioDeNotificaciones, Depends(obtener_servicio)]
RUTA = "/clientes/{id_cliente}"


class _Dto(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


class SolicitudDispositivoDto(_Dto):
    token: str = Field(min_length=1, max_length=LARGO_MAXIMO_TOKEN)
    plataforma: Plataforma = Plataforma.ANDROID


class DispositivoDto(_Dto):
    id: str
    plataforma: Plataforma
    registrado_en: datetime
    actualizado_en: datetime


class NotificacionDto(_Dto):
    id: str
    titulo: str
    cuerpo: str
    datos: dict[str, str]
    creada_en: datetime | None
    estado: EstadoEntrega
    leida: bool


def _dispositivo(d: Dispositivo) -> DispositivoDto:
    return DispositivoDto(
        id=d.id_dispositivo,
        plataforma=d.plataforma,
        registrado_en=d.registrado_en,
        actualizado_en=d.actualizado_en,
    )


def _notificacion(n: Notificacion) -> NotificacionDto:
    return NotificacionDto(
        id=n.id_notificacion,
        titulo=n.titulo,
        cuerpo=n.cuerpo,
        datos=n.datos,
        creada_en=n.creada_en,
        estado=n.estado,
        leida=n.leida,
    )


@router.post(
    RUTA + "/dispositivos",
    response_model=DispositivoDto,
    status_code=status.HTTP_201_CREATED,
    summary="Registra el dispositivo del cliente para recibir push",
)
def registrar(
    id_cliente: str, solicitud: SolicitudDispositivoDto, servicio: Servicio, response: Response
) -> DispositivoDto:
    """201 si es nuevo; 200 si el token ya estaba (queda con este cliente). 422
    `token_push_invalido`."""
    dispositivo, nuevo = servicio.registrar_dispositivo(
        id_cliente, solicitud.token, solicitud.plataforma
    )
    if not nuevo:
        response.status_code = status.HTTP_200_OK
    return _dispositivo(dispositivo)


@router.delete(
    RUTA + "/dispositivos/{id_dispositivo}",
    status_code=status.HTTP_204_NO_CONTENT,
    summary="Da de baja el dispositivo (cierre de sesión)",
)
def dar_de_baja(id_cliente: str, id_dispositivo: str, servicio: Servicio) -> Response:
    servicio.dar_de_baja(id_cliente, id_dispositivo)
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.get(
    RUTA + "/notificaciones",
    response_model=tuple[NotificacionDto, ...],
    summary="Notificaciones del cliente, de la más reciente a la más antigua",
)
def listar(
    id_cliente: str, servicio: Servicio, limite: Annotated[int, Query(ge=1, le=100)] = 50
) -> tuple[NotificacionDto, ...]:
    return tuple(_notificacion(n) for n in servicio.notificaciones(id_cliente, limite))


@router.post(
    RUTA + "/notificaciones/{id_notificacion}/lectura",
    status_code=status.HTTP_204_NO_CONTENT,
    summary="Marca una notificación como leída",
)
def marcar_leida(id_cliente: str, id_notificacion: str, servicio: Servicio) -> Response:
    servicio.marcar_leida(id_cliente, id_notificacion)
    return Response(status_code=status.HTTP_204_NO_CONTENT)
