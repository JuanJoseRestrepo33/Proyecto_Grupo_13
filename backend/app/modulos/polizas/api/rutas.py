"""Rutas HTTP del módulo de pólizas, bajo la fachada /v1.

Por ahora solo lista las pólizas del cliente, que es lo que el asistente de reporte de
siniestros necesita para elegir la afectada (WEB-F05). La consulta completa es de WEB-F04.
"""

from __future__ import annotations

from datetime import date
from typing import Annotated

from fastapi import APIRouter, Depends
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
