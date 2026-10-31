"""Verificación de identidad del canal móvil (MOB-F01, WEB-F13), según `contratos/bff-movil`.

El cliente sale de la sesión y el documento no pasa por aquí: el núcleo lo toma de la cuenta.
La respuesta se traduce campo por campo; `solicitada_en` y `proveedor` no llegan a la app.
"""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, status

from app.comun.errores import ErrorBff
from app.comun.sesion import sesion_no_vigente

from .dependencias import CANAL, DESAFIO, ClienteMovil, Nucleo
from .sesiones import RUTA_NUCLEO

router = APIRouter(prefix="/identidad/verificacion", tags=["identidad"])

VERIFICACION_NO_ENCONTRADA = "verificacion_no_encontrada"


def a_verificacion(verificacion: dict[str, Any]) -> dict[str, object]:
    """`motivo` y `reintento_permitido` solo van si hay rechazo: el contrato no admite null."""
    respuesta: dict[str, object] = {
        "id": verificacion["id"],
        "estado": verificacion["estado"],
        "actualizada_en": verificacion["actualizada_en"],
    }
    for campo in ("motivo", "reintento_permitido"):
        if verificacion.get(campo) is not None:
            respuesta[campo] = verificacion[campo]
    return respuesta


@router.get("", summary="Estado de la última verificación de identidad")
def obtener(cliente: ClienteMovil, nucleo: Nucleo) -> dict[str, object]:
    """404 `verificacion_no_encontrada` si nunca solicitó una: la app hace entonces el `POST`."""
    verificacion = nucleo.get(
        f"{RUTA_NUCLEO}/clientes/{cliente.id_cliente}/verificaciones/ultima",
        si_no_existe=VERIFICACION_NO_ENCONTRADA,
    )
    return a_verificacion(verificacion)


@router.post(
    "",
    status_code=status.HTTP_202_ACCEPTED,
    summary="Envía los datos del registro al proveedor KYC/AML",
)
def solicitar(cliente: ClienteMovil, nucleo: Nucleo) -> dict[str, object]:
    """409 `identidad_ya_verificada` o `verificacion_en_curso`: la app vuelve a consultar. Si
    la cuenta ya no existe en el núcleo, la sesión no es vigente, como en `/sesion`."""
    try:
        verificacion = nucleo.post(
            f"{RUTA_NUCLEO}/clientes/{cliente.id_cliente}/verificaciones", canal=CANAL
        )
    except ErrorBff as error:
        if error.estado == status.HTTP_404_NOT_FOUND:
            raise sesion_no_vigente(DESAFIO) from error
        raise
    return a_verificacion(verificacion)
