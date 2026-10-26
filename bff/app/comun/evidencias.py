"""Evidencia del siniestro contra el núcleo, igual para los dos canales (MOB-F05).

El canal manda un formulario `multipart/form-data` con el archivo; el núcleo lo recibe como
cuerpo crudo con su tipo. La fecha de la cámara, la ubicación y el lugar viajan como
parámetros. La evidencia agregada desde un canal se ve en el otro (criterio 5).
"""

from __future__ import annotations

from datetime import datetime
from typing import Any

from fastapi import UploadFile

from .errores import ErrorBff
from .nucleo import ClienteNucleo, segmento
from .siniestros import ruta_siniestro

#: El mismo límite del núcleo; el BFF corta antes para no reenviar lo que se va a rechazar.
TAMANO_MAXIMO = 10 * 1024 * 1024
NO_ENCONTRADA = "evidencia_no_encontrada"

#: Errores de datos que el núcleo responde como 400 y los contratos como 422, con su campo.
_CAMPO_POR_CODIGO = {
    "formato_no_admitido": "archivo",
    "archivo_vacio": "archivo",
    "ubicacion_invalida": "latitud",
    "fecha_de_captura_invalida": "tomada_en",
}


def _demasiado_grande() -> ErrorBff:
    return ErrorBff(
        413,
        "archivo_demasiado_grande",
        "El archivo supera los 10 MB",
        extra={"maximo": TAMANO_MAXIMO},
    )


def a_evidencia(evidencia: dict[str, Any]) -> dict[str, object]:
    return {
        "id": evidencia["id"],
        "tipo_contenido": evidencia["tipo_contenido"],
        "tamano": evidencia["tamano"],
        "nombre_original": evidencia["nombre_original"],
        "canal": evidencia["canal"],
        "cargada_en": evidencia["cargada_en"],
        "tomada_en": evidencia.get("tomada_en"),
        "latitud": evidencia.get("latitud"),
        "longitud": evidencia.get("longitud"),
        "lugar": evidencia.get("lugar"),
    }


def _ruta(id_cliente: str, id_siniestro: str) -> str:
    return f"{ruta_siniestro(id_cliente, id_siniestro)}/evidencias"


async def leer_archivo(archivo: UploadFile) -> bytes:
    """El contenido del archivo, salvo que pase de 10 MB: entonces no se reenvía."""
    contenido = await archivo.read(TAMANO_MAXIMO + 1)
    if len(contenido) > TAMANO_MAXIMO:
        raise _demasiado_grande()
    return contenido


def adjuntar(
    nucleo: ClienteNucleo,
    id_cliente: str,
    canal: str,
    id_siniestro: str,
    *,
    contenido: bytes,
    tipo_contenido: str,
    nombre_original: str | None,
    tomada_en: datetime | None = None,
    latitud: float | None = None,
    longitud: float | None = None,
    lugar: str | None = None,
) -> dict[str, object]:
    parametros = {
        "nombre_original": nombre_original,
        "tomada_en": tomada_en.isoformat() if tomada_en else None,
        "latitud": latitud,
        "longitud": longitud,
        "lugar": lugar,
    }
    try:
        respuesta = nucleo.solicitar(
            "POST",
            _ruta(id_cliente, id_siniestro),
            canal=canal,
            contenido=contenido,
            encabezados={"Content-Type": tipo_contenido or "application/octet-stream"},
            params={k: v for k, v in parametros.items() if v is not None},
            si_no_existe="siniestro_no_encontrado",
        )
    except ErrorBff as error:
        if error.codigo == "archivo_demasiado_grande":
            raise _demasiado_grande() from error
        if error.codigo in _CAMPO_POR_CODIGO:
            error.estado = 422
            error.extra["campos"] = [
                {"campo": _CAMPO_POR_CODIGO[error.codigo], "codigo": error.codigo}
            ]
        raise
    return a_evidencia(respuesta.json())


def listar(nucleo: ClienteNucleo, id_cliente: str, id_siniestro: str) -> list[dict[str, object]]:
    evidencias = nucleo.get(_ruta(id_cliente, id_siniestro), si_no_existe="siniestro_no_encontrado")
    return [a_evidencia(e) for e in evidencias]


def descargar(
    nucleo: ClienteNucleo, id_cliente: str, id_siniestro: str, id_evidencia: str
) -> tuple[bytes, str, str]:
    """El archivo, su tipo y su `Content-Disposition`, tal como los entrega el núcleo."""
    respuesta = nucleo.solicitar(
        "GET",
        f"{_ruta(id_cliente, id_siniestro)}/{segmento(id_evidencia, NO_ENCONTRADA)}",
        si_no_existe=NO_ENCONTRADA,
    )
    return (
        respuesta.content,
        respuesta.headers.get("content-type", "application/octet-stream"),
        respuesta.headers.get("content-disposition", "inline"),
    )
