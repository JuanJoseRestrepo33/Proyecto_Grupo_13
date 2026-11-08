"""Rutas HTTP del módulo de pagos, bajo la fachada /v1.

El cobro se pide desde el checkout de suscripción (`/v1/suscripcion/...`), que sabe qué se
cobra y cuánto. Aquí solo se consulta un pago por su concepto, por ejemplo para conciliar o
para una prueba de extremo a extremo.
"""

from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends

from ..aplicacion.servicio import ServicioDePagos
from .dependencias import obtener_servicio
from .esquemas import PagoDto, a_pago_dto

router = APIRouter(prefix="/pagos", tags=["pagos"])

Servicio = Annotated[ServicioDePagos, Depends(obtener_servicio)]


@router.get(
    "/conceptos/{concepto}/pagos/{id_pago}",
    response_model=PagoDto,
    summary="Un pago del concepto, con su estado actual en la pasarela",
)
def obtener(concepto: str, id_pago: str, servicio: Servicio) -> PagoDto:
    """Un pago pendiente se resuelve con la pasarela al consultarlo. 404 `pago_no_encontrado`."""
    return a_pago_dto(servicio.consultar(concepto, id_pago))
