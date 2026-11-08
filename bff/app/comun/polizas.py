"""Pólizas del cliente contra el núcleo, iguales para los dos canales (WEB-F04).

La póliza emitida en la web es la misma que ve la app sin hacer nada más (criterio 2): los
dos routers leen del mismo núcleo con esta misma traducción. Solo cambia el prefijo de la
`documento_url`, que apunta al router del canal que pregunta.

El documento (WEB-F03, criterio 4) sale por aquí con la sesión del cliente; el bucket es
privado y el canal nunca recibe una URL del almacén.
"""

from __future__ import annotations

import logging
from typing import Any

from .nucleo import ClienteNucleo, segmento

RUTA_NUCLEO = "/v1/polizas"
NO_ENCONTRADA = "poliza_no_encontrada"
#: Hoy todo se vende en pesos. Si el núcleo manda una prima sin `moneda` se asume esta, pero
#: queda en el log: el día que haya otra moneda, una prima sin ella es un error del núcleo.
MONEDA_POR_DEFECTO = "COP"

_log = logging.getLogger(__name__)


def _ruta(id_cliente: str) -> str:
    return f"{RUTA_NUCLEO}/clientes/{id_cliente}/polizas"


def _moneda(poliza: dict[str, Any]) -> str:
    moneda = poliza.get("moneda")
    if moneda:
        return str(moneda)
    _log.warning(
        "La póliza %s trae prima sin moneda; se asume %s", poliza["numero"], MONEDA_POR_DEFECTO
    )
    return MONEDA_POR_DEFECTO


def a_resumen(poliza: dict[str, Any], prefijo: str) -> dict[str, object]:
    prima = poliza.get("prima_mensual")
    documento = None
    if poliza.get("tiene_documento"):
        documento = f"{prefijo}/polizas/{poliza['numero']}/documento"
    return {
        "numero": poliza["numero"],
        "producto": poliza["producto"],
        "inicio": poliza["inicio"],
        "fin": poliza["fin"],
        "estado": poliza["estado"],
        "nombre_producto": poliza.get("nombre_producto"),
        "prima_mensual": None if prima is None else {"valor": prima, "moneda": _moneda(poliza)},
        "documento_url": documento,
    }


def a_detalle(poliza: dict[str, Any], prefijo: str) -> dict[str, object]:
    return {
        **a_resumen(poliza, prefijo),
        "coberturas": [
            {"nombre": c["nombre"], "limite": c["limite"]} for c in poliza.get("coberturas", [])
        ],
        "deducible": poliza.get("deducible"),
        "valor_asegurado": poliza.get("valor_asegurado"),
        "emitida_en": poliza.get("emitida_en"),
    }


def listar(nucleo: ClienteNucleo, id_cliente: str, prefijo: str) -> list[dict[str, object]]:
    """De la más reciente a la más antigua. Vacía si no hay: el canal invita a cotizar."""
    return [a_resumen(p, prefijo) for p in nucleo.get(_ruta(id_cliente))]


def detalle(nucleo: ClienteNucleo, id_cliente: str, numero: str, prefijo: str) -> dict[str, object]:
    poliza = nucleo.get(
        f"{_ruta(id_cliente)}/{segmento(numero, NO_ENCONTRADA)}", si_no_existe=NO_ENCONTRADA
    )
    return a_detalle(poliza, prefijo)


def documento(nucleo: ClienteNucleo, id_cliente: str, numero: str) -> tuple[bytes, str]:
    """El PDF y su `Content-Disposition`. 404 `poliza_no_encontrada` o
    `documento_no_disponible` (las pólizas de prueba de la semilla no tienen)."""
    respuesta = nucleo.solicitar(
        "GET", f"{_ruta(id_cliente)}/{segmento(numero, NO_ENCONTRADA)}/documento"
    )
    return (
        respuesta.content,
        respuesta.headers.get("content-disposition", f'attachment; filename="{numero}.pdf"'),
    )
