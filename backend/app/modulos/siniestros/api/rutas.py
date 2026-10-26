"""Rutas HTTP del módulo de siniestros, bajo la fachada /v1.

El núcleo no autentica: el BFF resuelve la sesión y pasa el cliente en la ruta, el canal en
`X-Canal` y la clave de idempotencia del reporte en `Idempotency-Key`. Un siniestro de otro
cliente responde 404, igual que si no existiera.

La evidencia llega como el cuerpo crudo de la solicitud, con su `Content-Type`: el BFF ya
desarmó el formulario del canal. El núcleo deja de leer pasados los 10 MB.
"""

from __future__ import annotations

from typing import Annotated
from urllib.parse import quote

from fastapi import APIRouter, Depends, Header, Query, Request, Response, status
from starlette.concurrency import run_in_threadpool

from ..aplicacion.servicio import ServicioDeSiniestros
from ..dominio.evidencias import TAMANO_MAXIMO, ArchivoDemasiadoGrandeError
from .dependencias import obtener_servicio
from .esquemas import (
    Canal,
    EvidenciaDto,
    SiniestroDto,
    SolicitudReporteDto,
    a_evidencia_dto,
    a_siniestro_dto,
)

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


# -- evidencia (MOB-F05) -----------------------------------------------------------------

RUTA_EVIDENCIAS = RUTA_CLIENTE + "/{id_siniestro}/evidencias"


async def _leer_con_tope(request: Request) -> bytes:
    """El cuerpo completo, salvo que pase de 10 MB: entonces se deja de leer y se rechaza."""
    partes, leidos = [], 0
    async for parte in request.stream():
        leidos += len(parte)
        if leidos > TAMANO_MAXIMO:
            raise ArchivoDemasiadoGrandeError("El archivo supera los 10 MB", maximo=TAMANO_MAXIMO)
        partes.append(parte)
    return b"".join(partes)


@router.post(
    RUTA_EVIDENCIAS,
    response_model=EvidenciaDto,
    status_code=status.HTTP_201_CREATED,
    summary="Adjunta una evidencia (foto, video o documento) a un siniestro",
)
async def adjuntar_evidencia(
    id_cliente: str,
    id_siniestro: str,
    request: Request,
    servicio: Servicio,
    canal: CanalDep = Canal.WEB,
    nombre_original: Annotated[str | None, Query(max_length=255)] = None,
) -> EvidenciaDto:
    """El cuerpo es el archivo y `Content-Type` su tipo. 400 `formato_no_admitido`,
    `archivo_demasiado_grande` o `archivo_vacio`; 409 `siniestro_cerrado`; 503
    `almacenamiento_no_disponible`."""
    contenido = await _leer_con_tope(request)
    evidencia = await run_in_threadpool(
        servicio.adjuntar_evidencia,
        id_cliente,
        id_siniestro,
        request.headers.get("content-type", ""),
        contenido,
        nombre_original,
        str(canal),
    )
    return a_evidencia_dto(evidencia)


@router.get(
    RUTA_EVIDENCIAS,
    response_model=tuple[EvidenciaDto, ...],
    summary="Evidencias del siniestro, en el orden en que se cargaron",
)
def evidencias(id_cliente: str, id_siniestro: str, servicio: Servicio) -> tuple[EvidenciaDto, ...]:
    return tuple(a_evidencia_dto(e) for e in servicio.evidencias(id_cliente, id_siniestro))


@router.get(
    RUTA_EVIDENCIAS + "/{id_evidencia}",
    response_class=Response,
    summary="Contenido de una evidencia",
)
def archivo_de_evidencia(
    id_cliente: str, id_siniestro: str, id_evidencia: str, servicio: Servicio
) -> Response:
    """El archivo con su tipo. 404 `evidencia_no_encontrada` si no es del siniestro."""
    evidencia, contenido = servicio.archivo_de_evidencia(id_cliente, id_siniestro, id_evidencia)
    disposicion = "inline"
    if evidencia.nombre_original:
        disposicion += f"; filename*=UTF-8''{quote(evidencia.nombre_original)}"
    return Response(
        content=contenido,
        media_type=evidencia.tipo_contenido,
        headers={"Content-Disposition": disposicion, "X-Content-Type-Options": "nosniff"},
    )
