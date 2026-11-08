"""Rutas HTTP del módulo de pagos, bajo la fachada /v1.

El cobro se pide desde el checkout de suscripción (`/v1/suscripcion/...`), que sabe qué se
cobra y cuánto, y es allí donde un pago pendiente se resuelve con la pasarela y deja la
suscripción pagada. Aquí solo se **lee** un pago del cliente, tal como está guardado, por
ejemplo para conciliar o para una prueba de extremo a extremo: esta ruta no llama a la
pasarela ni escribe nada.
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
    "/clientes/{id_cliente}/conceptos/{concepto}/pagos/{id_pago}",
    response_model=PagoDto,
    summary="Un pago del cliente, tal como está guardado",
)
def obtener(id_cliente: str, concepto: str, id_pago: str, servicio: Servicio) -> PagoDto:
    """404 `pago_no_encontrado` si no existe, es de otro concepto o de otro cliente."""
    return a_pago_dto(servicio.leer(id_cliente, concepto, id_pago))
