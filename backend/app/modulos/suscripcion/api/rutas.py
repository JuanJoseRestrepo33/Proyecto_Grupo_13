"""Rutas HTTP del checkout (WEB-F03), bajo la fachada /v1.

El núcleo no autentica: el BFF resuelve la sesión y pasa el cliente en la ruta, el canal en
`X-Canal` y la clave de cada intento de pago en `Idempotency-Key`. Una suscripción de otro
cliente responde 404, igual que si no existiera.

    POST /suscripcion/clientes/{c}/suscripciones                       aceptar
    GET  /suscripcion/clientes/{c}/suscripciones/{id}                  consultar
    POST /suscripcion/clientes/{c}/suscripciones/{id}/pagos            pagar
    GET  /suscripcion/clientes/{c}/suscripciones/{id}/pagos/{id_pago}  estado del pago
    POST /suscripcion/clientes/{c}/suscripciones/{id}/firma            firmar y emitir
"""

from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends, Header, Response, status

from ..aplicacion.servicio import ServicioDeSuscripcion
from .dependencias import obtener_servicio
from .esquemas import (
    Canal,
    PagoDto,
    SolicitudFirmaDto,
    SolicitudPagoDto,
    SolicitudSuscripcionDto,
    SuscripcionDto,
    a_pago_dto,
    a_suscripcion_dto,
)

router = APIRouter(prefix="/suscripcion", tags=["suscripción"])

Servicio = Annotated[ServicioDeSuscripcion, Depends(obtener_servicio)]
CanalDep = Annotated[Canal, Header(alias="X-Canal", description="Canal que origina la solicitud")]
ClaveDep = Annotated[
    str,
    Header(
        alias="Idempotency-Key",
        min_length=8,
        max_length=128,
        description="Identifica el intento de pago; la misma clave nunca cobra dos veces",
    ),
]

RUTA = "/clientes/{id_cliente}/suscripciones"


def _dto(servicio: ServicioDeSuscripcion, suscripcion) -> SuscripcionDto:
    return a_suscripcion_dto(suscripcion, servicio.ultimo_pago(suscripcion))


@router.post(
    RUTA,
    response_model=SuscripcionDto,
    status_code=status.HTTP_201_CREATED,
    summary="Acepta una cotización vigente del cliente",
)
def aceptar(
    id_cliente: str,
    solicitud: SolicitudSuscripcionDto,
    servicio: Servicio,
    response: Response,
    canal: CanalDep = Canal.WEB,
) -> SuscripcionDto:
    """201 con la suscripción nueva; 200 con la misma si la cotización ya estaba aceptada.
    404 `cotizacion_no_encontrada`; 409 `cotizacion_vencida`."""
    suscripcion, nueva = servicio.aceptar(id_cliente, solicitud.id_cotizacion, str(canal))
    if not nueva:
        response.status_code = status.HTTP_200_OK
    return _dto(servicio, suscripcion)


@router.get(
    RUTA,
    response_model=tuple[SuscripcionDto, ...],
    summary="Suscripciones del cliente, de la más reciente a la más antigua",
)
def listar(id_cliente: str, servicio: Servicio) -> tuple[SuscripcionDto, ...]:
    return tuple(_dto(servicio, s) for s in servicio.del_cliente(id_cliente))


@router.get(
    RUTA + "/{id_suscripcion}",
    response_model=SuscripcionDto,
    summary="Una suscripción con su último pago y su póliza",
)
def obtener(id_cliente: str, id_suscripcion: str, servicio: Servicio) -> SuscripcionDto:
    return _dto(servicio, servicio.obtener(id_cliente, id_suscripcion))


@router.post(
    RUTA + "/{id_suscripcion}/pagos",
    response_model=PagoDto,
    status_code=status.HTTP_201_CREATED,
    summary="Paga la prima contra la pasarela simulada, sin cobro doble",
)
def pagar(
    id_cliente: str,
    id_suscripcion: str,
    solicitud: SolicitudPagoDto,
    servicio: Servicio,
    response: Response,
    clave: ClaveDep,
    canal: CanalDep = Canal.WEB,
) -> PagoDto:
    """201 con el intento nuevo (aprobado, rechazado o pendiente); 200 con `reintento: true`
    si la misma clave ya se procesó. 409 `pago_en_curso` o `pago_ya_registrado`; 422
    `clave_idempotencia_invalida`; 503 si la pasarela no responde (no hubo cobro)."""
    pago, nuevo = servicio.pagar(
        id_cliente, id_suscripcion, str(solicitud.medio), clave, str(canal)
    )
    if not nuevo:
        response.status_code = status.HTTP_200_OK
    return a_pago_dto(pago, reintento=not nuevo)


@router.get(
    RUTA + "/{id_suscripcion}/pagos/{id_pago}",
    response_model=PagoDto,
    summary="Estado actual de un pago, por ejemplo uno pendiente",
)
def consultar_pago(
    id_cliente: str, id_suscripcion: str, id_pago: str, servicio: Servicio
) -> PagoDto:
    return a_pago_dto(servicio.consultar_pago(id_cliente, id_suscripcion, id_pago))


@router.post(
    RUTA + "/{id_suscripcion}/firma",
    response_model=SuscripcionDto,
    status_code=status.HTTP_201_CREATED,
    summary="Firma electrónicamente y emite la póliza",
)
def firmar(
    id_cliente: str,
    id_suscripcion: str,
    solicitud: SolicitudFirmaDto,
    servicio: Servicio,
    response: Response,
) -> SuscripcionDto:
    """201 con la suscripción `emitida` y su póliza; 200 si ya estaba emitida (la misma
    póliza). 409 `pago_no_aprobado`; 422 `aceptacion_requerida`; 503 si el proveedor de firma
    no responde (no se emitió nada)."""
    suscripcion, nueva = servicio.firmar(id_cliente, id_suscripcion, solicitud.acepta_condiciones)
    if not nueva:
        response.status_code = status.HTTP_200_OK
    return _dto(servicio, suscripcion)
