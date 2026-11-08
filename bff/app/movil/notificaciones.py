"""Dispositivo y notificaciones push del canal móvil (MOB-F06), según `contratos/bff-movil`.

El push es propio de la app: estas rutas no existen en el router web.

- Al iniciar sesión, la app registra su token de push (`POST /dispositivos`). Es idempotente,
  así que puede hacerlo en cada arranque (criterio 4).
- Al cerrar sesión, lo da de baja (`DELETE /dispositivos/{id}`).
- El proveedor de push es simulado: la app lee sus notificaciones con `GET /notificaciones` y
  las muestra como push local. Cada una trae en `datos` el siniestro que hay que abrir al
  tocarla (criterio 2).
"""

from __future__ import annotations

from typing import Annotated, Any, Literal

from fastapi import APIRouter, Query, Response, status
from pydantic import BaseModel, Field

from app.comun.nucleo import segmento

from .dependencias import ClienteMovil, Nucleo

router = APIRouter(tags=["notificaciones"])

DISPOSITIVO_NO_ENCONTRADO = "dispositivo_no_encontrado"
NOTIFICACION_NO_ENCONTRADA = "notificacion_no_encontrada"


class SolicitudDispositivo(BaseModel):
    token_push: str = Field(min_length=1, max_length=4096)
    plataforma: Literal["android"] = "android"


def _ruta(id_cliente: str) -> str:
    return f"/v1/notificaciones/clientes/{id_cliente}"


def a_dispositivo(dispositivo: dict[str, Any]) -> dict[str, object]:
    return {
        "id": dispositivo["id"],
        "plataforma": dispositivo["plataforma"],
        "registrado_en": dispositivo["registrado_en"],
    }


def a_notificacion(notificacion: dict[str, Any]) -> dict[str, object]:
    return {
        "id": notificacion["id"],
        "titulo": notificacion["titulo"],
        "cuerpo": notificacion["cuerpo"],
        "datos": notificacion["datos"],
        "creada_en": notificacion["creada_en"],
        "leida": notificacion["leida"],
    }


@router.post(
    "/dispositivos",
    status_code=status.HTTP_201_CREATED,
    summary="Registra el dispositivo para recibir push",
)
def registrar(
    solicitud: SolicitudDispositivo, response: Response, cliente: ClienteMovil, nucleo: Nucleo
) -> dict[str, object]:
    """201 si es nuevo; 200 si el token ya estaba registrado (queda con esta cuenta)."""
    respuesta = nucleo.solicitar(
        "POST",
        f"{_ruta(cliente.id_cliente)}/dispositivos",
        json={"token": solicitud.token_push, "plataforma": solicitud.plataforma},
    )
    response.status_code = respuesta.status_code
    return a_dispositivo(respuesta.json())


@router.delete(
    "/dispositivos/{id_dispositivo}",
    status_code=status.HTTP_204_NO_CONTENT,
    summary="Da de baja el dispositivo al cerrar sesión",
)
def dar_de_baja(id_dispositivo: str, cliente: ClienteMovil, nucleo: Nucleo) -> Response:
    nucleo.solicitar(
        "DELETE",
        f"{_ruta(cliente.id_cliente)}/dispositivos/"
        f"{segmento(id_dispositivo, DISPOSITIVO_NO_ENCONTRADO)}",
        si_no_existe=DISPOSITIVO_NO_ENCONTRADO,
    )
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.get("/notificaciones", summary="Notificaciones del cliente, de la más reciente")
def listar(
    cliente: ClienteMovil,
    nucleo: Nucleo,
    limite: Annotated[int, Query(ge=1, le=100)] = 50,
) -> list[dict[str, object]]:
    notificaciones = nucleo.get(
        f"{_ruta(cliente.id_cliente)}/notificaciones", params={"limite": limite}
    )
    return [a_notificacion(n) for n in notificaciones]


@router.post(
    "/notificaciones/{id_notificacion}/lectura",
    status_code=status.HTTP_204_NO_CONTENT,
    summary="Marca una notificación como leída",
)
def marcar_leida(id_notificacion: str, cliente: ClienteMovil, nucleo: Nucleo) -> Response:
    nucleo.solicitar(
        "POST",
        f"{_ruta(cliente.id_cliente)}/notificaciones/"
        f"{segmento(id_notificacion, NOTIFICACION_NO_ENCONTRADA)}/lectura",
        si_no_existe=NOTIFICACION_NO_ENCONTRADA,
    )
    return Response(status_code=status.HTTP_204_NO_CONTENT)
