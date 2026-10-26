"""Rutas HTTP del módulo de siniestros, bajo la fachada /v1.

El núcleo no autentica: el BFF resuelve la sesión y pasa el cliente en la ruta, el canal en
`X-Canal` y la clave de idempotencia del reporte en `Idempotency-Key`. Un siniestro de otro
cliente responde 404, igual que si no existiera.
"""

from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends, Header, Response, status

from ..aplicacion.servicio import ServicioDeSiniestros
from .dependencias import obtener_servicio
from .esquemas import Canal, SiniestroDto, SolicitudReporteDto, a_siniestro_dto

router = APIRouter(prefix="/siniestros", tags=["siniestros"])

Servicio = Annotated[ServicioDeSiniestros, Depends(obtener_servicio)]
CanalDep = Annotated[Canal, Header(alias="X-Canal", description="Canal que origina el reporte")]
ClaveDep = Annotated[
    str | None,
    Header(
        alias="Idempotency-Key",
        min_length=8,
        max_length=128,
        description="La misma clave devuelve el mismo siniestro",
    ),
]

RUTA_CLIENTE = "/clientes/{id_cliente}/siniestros"


@router.post(
    RUTA_CLIENTE,
    response_model=SiniestroDto,
    status_code=status.HTTP_201_CREATED,
    summary="Reporta un siniestro sobre una póliza del cliente",
)
def reportar(
    id_cliente: str,
    solicitud: SolicitudReporteDto,
    servicio: Servicio,
    response: Response,
    canal: CanalDep = Canal.WEB,
    clave: ClaveDep = None,
) -> SiniestroDto:
    """201 con un siniestro nuevo; 200 con el mismo si la clave ya se usó con los mismos
    datos. 404 `poliza_no_encontrada`; 409 `evento_fuera_de_vigencia` o
    `clave_idempotencia_en_uso`; 400 `fecha_futura`."""
    siniestro, nuevo = servicio.reportar(id_cliente, solicitud.a_datos(), str(canal), clave)
    if not nuevo:
        response.status_code = status.HTTP_200_OK
    return a_siniestro_dto(siniestro)


@router.get(
    RUTA_CLIENTE,
    response_model=tuple[SiniestroDto, ...],
    summary="Siniestros del cliente, del más reciente al más antiguo",
)
def listar(id_cliente: str, servicio: Servicio) -> tuple[SiniestroDto, ...]:
    return tuple(a_siniestro_dto(s) for s in servicio.del_cliente(id_cliente))


@router.get(
    RUTA_CLIENTE + "/{id_siniestro}",
    response_model=SiniestroDto,
    summary="Detalle de un siniestro",
)
def obtener(id_cliente: str, id_siniestro: str, servicio: Servicio) -> SiniestroDto:
    return a_siniestro_dto(servicio.obtener(id_cliente, id_siniestro))
