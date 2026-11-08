"""Pólizas del cliente contra el núcleo, iguales para los dos canales.

El documento de una póliza emitida (WEB-F03, criterio 4) sale por aquí con la sesión del
cliente; el bucket es privado y el canal nunca recibe una URL del almacén.
"""

from __future__ import annotations

from .nucleo import ClienteNucleo, segmento

RUTA_NUCLEO = "/v1/polizas"
NO_ENCONTRADA = "poliza_no_encontrada"


def documento(nucleo: ClienteNucleo, id_cliente: str, numero: str) -> tuple[bytes, str]:
    """El PDF y su `Content-Disposition`. 404 `poliza_no_encontrada` o
    `documento_no_disponible` (las pólizas de prueba de la semilla no tienen)."""
    respuesta = nucleo.solicitar(
        "GET",
        f"{RUTA_NUCLEO}/clientes/{id_cliente}/polizas/{segmento(numero, NO_ENCONTRADA)}/documento",
    )
    return (
        respuesta.content,
        respuesta.headers.get("content-disposition", f'attachment; filename="{numero}.pdf"'),
    )
