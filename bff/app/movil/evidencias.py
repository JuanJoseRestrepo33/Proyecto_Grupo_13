"""Rutas de evidencia del canal móvil (MOB-F05), según `contratos/bff-movil`.

El cliente manda el archivo en un formulario `multipart/form-data` (campo `archivo`); la
orquestación, igual para los dos canales, está en `app.comun.evidencias`.
"""

from __future__ import annotations

from datetime import datetime
from typing import Annotated

from fastapi import APIRouter, File, Form, Response, UploadFile, status

from app.comun import evidencias

from .dependencias import CANAL, ClienteMovil, Nucleo

router = APIRouter(prefix="/siniestros/{id_siniestro}/evidencias", tags=["siniestros"])


@router.post("", status_code=status.HTTP_201_CREATED, summary="Adjunta una evidencia")
async def adjuntar(
    id_siniestro: str,
    cliente: ClienteMovil,
    nucleo: Nucleo,
    archivo: Annotated[UploadFile, File(description="Foto, video o documento, hasta 10 MB")],
    tomada_en: Annotated[datetime | None, Form()] = None,
    latitud: Annotated[float | None, Form()] = None,
    longitud: Annotated[float | None, Form()] = None,
    lugar: Annotated[str | None, Form(max_length=200)] = None,
) -> dict[str, object]:
    """413 `archivo_demasiado_grande`; 422 `formato_no_admitido` o `archivo_vacio` sobre el
    campo `archivo`; 409 `siniestro_cerrado`; 503 `almacenamiento_no_disponible`."""
    contenido = await evidencias.leer_archivo(archivo)
    return evidencias.adjuntar(
        nucleo,
        cliente.id_cliente,
        CANAL,
        id_siniestro,
        contenido=contenido,
        tipo_contenido=archivo.content_type or "",
        nombre_original=archivo.filename,
        tomada_en=tomada_en,
        latitud=latitud,
        longitud=longitud,
        lugar=lugar,
    )


@router.get("", summary="Evidencias del siniestro, en el orden en que se cargaron")
def listar(id_siniestro: str, cliente: ClienteMovil, nucleo: Nucleo) -> list[dict[str, object]]:
    return evidencias.listar(nucleo, cliente.id_cliente, id_siniestro)


@router.get("/{id_evidencia}", response_class=Response, summary="Contenido de una evidencia")
def descargar(
    id_siniestro: str, id_evidencia: str, cliente: ClienteMovil, nucleo: Nucleo
) -> Response:
    contenido, tipo, disposicion = evidencias.descargar(
        nucleo, cliente.id_cliente, id_siniestro, id_evidencia
    )
    return Response(
        content=contenido,
        media_type=tipo,
        headers={"Content-Disposition": disposicion, "X-Content-Type-Options": "nosniff"},
    )
