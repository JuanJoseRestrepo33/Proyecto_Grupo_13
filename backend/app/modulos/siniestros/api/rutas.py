"""Rutas HTTP del módulo de siniestros, bajo la fachada /v1.

El núcleo no autentica: el BFF resuelve la sesión y pasa el cliente en la ruta, el canal en
`X-Canal` y la clave de idempotencia del reporte en `Idempotency-Key`. Un siniestro de otro
cliente responde 404, igual que si no existiera.

La evidencia llega como el cuerpo crudo de la solicitud, con su `Content-Type`: el BFF ya
desarmó el formulario del canal. El núcleo deja de leer pasados los 10 MB.

El cambio de estado (WEB-F06) es una operación interna, sin cliente en la ruta y sin
interfaz: la usan *staging* y las pruebas. Exige `X-Token-Interno` igual a
`SINIESTROS_TOKEN_INTERNO`; si la variable no está definida, la ruta no existe (404).
"""

from __future__ import annotations

from datetime import datetime
from typing import Annotated
from urllib.parse import quote

from fastapi import APIRouter, Depends, Header, Query, Request, Response, status
from starlette.concurrency import run_in_threadpool

from ..aplicacion.servicio import ServicioDeSiniestros
from ..dominio.evidencias import TAMANO_MAXIMO, ArchivoDemasiadoGrandeError, Captura
from .dependencias import exigir_token_interno, obtener_servicio
from .esquemas import (
    CambioDeEstadoDto,
    Canal,
    EvidenciaDto,
    SiniestroDetalleDto,
    SiniestroDto,
    SolicitudReporteDto,
    a_detalle_dto,
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
    response_model=SiniestroDetalleDto,
    summary="Detalle de un siniestro con su historial y sus requerimientos",
)
def obtener(id_cliente: str, id_siniestro: str, servicio: Servicio) -> SiniestroDetalleDto:
    """WEB-F06: el estado actual (criterio 1), el historial de eventos con su fecha
    (criterio 4) y los requerimientos, pendientes o atendidos (criterio 5)."""
    return a_detalle_dto(
        servicio.obtener(id_cliente, id_siniestro),
        servicio.historial(id_cliente, id_siniestro),
        servicio.requerimientos(id_cliente, id_siniestro),
    )


@router.post(
    "/{id_siniestro}/estado",
    response_model=SiniestroDto,
    summary="Cambia el estado de un siniestro (operación interna de staging y pruebas)",
    dependencies=[Depends(exigir_token_interno)],
)
def cambiar_estado(
    id_siniestro: str, cambio: CambioDeEstadoDto, servicio: Servicio
) -> SiniestroDto:
    """409 `transicion_no_permitida` o `cambio_de_estado_concurrente`; 422
    `requerimientos_obligatorios` al pedir información sin decir qué falta; 404
    `siniestro_no_encontrado`. Audita y publica `siniestro-estado-cambiado`."""
    siniestro = servicio.cambiar_estado(
        id_siniestro,
        cambio.estado,
        motivo=cambio.motivo,
        requerimientos=[r.descripcion for r in cambio.requerimientos],
    )
    return a_siniestro_dto(siniestro)


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
    tomada_en: Annotated[datetime | None, Query(description="Fecha y hora de la cámara")] = None,
    latitud: Annotated[float | None, Query()] = None,
    longitud: Annotated[float | None, Query()] = None,
    lugar: Annotated[str | None, Query(max_length=200)] = None,
    id_requerimiento: Annotated[
        str | None, Query(max_length=64, description="Requerimiento pendiente que responde")
    ] = None,
) -> EvidenciaDto:
    """El cuerpo es el archivo y `Content-Type` su tipo. 400 `formato_no_admitido`,
    `archivo_demasiado_grande` o `archivo_vacio`; 409 `siniestro_cerrado`; 503
    `almacenamiento_no_disponible`. La fecha de la cámara y la ubicación son opcionales; sin
    permiso de ubicación, el cliente puede escribir el `lugar`. Con `id_requerimiento`
    responde un requerimiento pendiente (WEB-F06): 404 `requerimiento_no_encontrado`, 409
    `requerimiento_ya_atendido`."""
    contenido = await _leer_con_tope(request)
    evidencia = await run_in_threadpool(
        servicio.adjuntar_evidencia,
        id_cliente,
        id_siniestro,
        request.headers.get("content-type", ""),
        contenido,
        nombre_original,
        str(canal),
        Captura(tomada_en, latitud, longitud, lugar),
        id_requerimiento,
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
