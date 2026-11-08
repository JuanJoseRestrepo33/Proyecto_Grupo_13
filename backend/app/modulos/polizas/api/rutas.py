"""Rutas HTTP del módulo de pólizas, bajo la fachada /v1.

Lista las pólizas del cliente (el asistente de siniestros elige ahí la afectada, WEB-F05) y
entrega el documento PDF de una póliza emitida (WEB-F03, criterio 4). El núcleo no
autentica: el BFF resuelve la sesión y pasa el cliente en la ruta. Una póliza de otro
cliente responde 404, igual que si no existiera.
"""

from __future__ import annotations

from datetime import date
from typing import Annotated

from fastapi import APIRouter, Depends, Response
from pydantic import BaseModel, ConfigDict

from ..aplicacion.servicio import ServicioDePolizas
from ..dominio.modelo import EstadoPoliza
from .dependencias import obtener_servicio

router = APIRouter(prefix="/polizas", tags=["polizas"])

Servicio = Annotated[ServicioDePolizas, Depends(obtener_servicio)]


class PolizaDto(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    numero: str
    producto: str
    inicio: date
    fin: date
    estado: EstadoPoliza


@router.get(
    "/clientes/{id_cliente}/polizas",
    response_model=tuple[PolizaDto, ...],
    summary="Pólizas del cliente, vigentes y vencidas",
)
def del_cliente(id_cliente: str, servicio: Servicio) -> tuple[PolizaDto, ...]:
    hoy = servicio.hoy()
    return tuple(
        PolizaDto(
            numero=p.numero,
            producto=p.producto,
            inicio=p.inicio,
            fin=p.fin,
            estado=p.estado(hoy),
        )
        for p in servicio.del_cliente(id_cliente)
    )


@router.get(
    "/clientes/{id_cliente}/polizas/{numero}/documento",
    response_class=Response,
    responses={200: {"content": {"application/pdf": {}}}},
    summary="Documento PDF de una póliza emitida",
)
def documento(id_cliente: str, numero: str, servicio: Servicio) -> Response:
    """404 `poliza_no_encontrada`, o `documento_no_disponible` si la póliza no tiene documento
    (las pólizas de prueba de la semilla)."""
    poliza, contenido = servicio.documento(numero, id_cliente)
    return Response(
        content=contenido,
        media_type="application/pdf",
        headers={"Content-Disposition": f'attachment; filename="{poliza.numero}.pdf"'},
    )
