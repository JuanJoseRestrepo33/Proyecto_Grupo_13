"""Pruebas de las rutas de pólizas de los dos routers (WEB-F04).

Cada prueba corre en la web y en la app: la póliza es la misma en los dos canales
(criterio 2); solo cambia el prefijo de `documento_url`.
"""

from __future__ import annotations

import httpx
import pytest

from .conftest import CLIENTE, al_nucleo, problema

NUCLEO = f"/v1/polizas/clientes/{CLIENTE}/polizas"
EMITIDA = {
    "numero": "POL-2026-000001",
    "producto": "auto",
    "inicio": "2026-11-08",
    "fin": "2027-11-07",
    "estado": "vigente",
    "nombre_producto": "Seguro de Auto Todo Riesgo",
    "prima_mensual": 132900,
    "moneda": "COP",
    "tiene_documento": True,
    "emitida": True,
}
DE_PRUEBA = {
    "numero": "POL-STG-AUTO-000318",
    "producto": "auto",
    "inicio": "2026-10-09",
    "fin": "2027-10-09",
    "estado": "vigente",
    "nombre_producto": None,
    "prima_mensual": None,
    "moneda": None,
    "tiene_documento": False,
    "emitida": False,
}
DETALLE = {
    **EMITIDA,
    "coberturas": [{"nombre": "Responsabilidad civil", "limite": "$1.000.000.000"}],
    "deducible": "5% del valor asegurado",
    "valor_asegurado": "$60.000.000",
    "emitida_en": "2026-11-08T15:01:00Z",
}


def _resumen(canal, poliza):
    documento = (
        f"{canal.prefijo}/polizas/{poliza['numero']}/documento"
        if poliza["tiene_documento"]
        else None
    )
    prima = poliza["prima_mensual"]
    return {
        "numero": poliza["numero"],
        "producto": poliza["producto"],
        "inicio": poliza["inicio"],
        "fin": poliza["fin"],
        "estado": poliza["estado"],
        "nombre_producto": poliza["nombre_producto"],
        "prima_mensual": None if prima is None else {"valor": prima, "moneda": "COP"},
        "documento_url": documento,
    }


@pytest.mark.parametrize("ruta", ["/polizas", "/polizas/POL-1", "/polizas/POL-1/documento"])
def test_sin_sesion_toda_ruta_es_401(cliente, nucleo, canal, ruta):
    respuesta = cliente.get(f"{canal.prefijo}{ruta}")

    assert respuesta.status_code == 401
    assert al_nucleo(nucleo) == []


def test_la_lista_trae_numero_producto_vigencia_estado_y_documento(con_sesion, nucleo, canal):
    """Criterio 1. La `documento_url` apunta al router del canal que pregunta."""
    nucleo.responder("GET", NUCLEO, 200, [EMITIDA, DE_PRUEBA])

    respuesta = con_sesion.get(f"{canal.prefijo}/polizas")

    assert respuesta.json() == [_resumen(canal, EMITIDA), _resumen(canal, DE_PRUEBA)]


def test_sin_polizas_la_lista_es_vacia(con_sesion, nucleo, canal):
    """Criterio 3: el canal muestra el estado vacío que lleva a cotizar."""
    nucleo.responder("GET", NUCLEO, 200, [])

    assert con_sesion.get(f"{canal.prefijo}/polizas").json() == []


def test_el_detalle_trae_coberturas_deducible_y_limites(con_sesion, nucleo, canal):
    """Criterio 4."""
    nucleo.responder("GET", f"{NUCLEO}/POL-2026-000001", 200, DETALLE)

    respuesta = con_sesion.get(f"{canal.prefijo}/polizas/POL-2026-000001")

    assert respuesta.json() == {
        **_resumen(canal, EMITIDA),
        "coberturas": DETALLE["coberturas"],
        "deducible": "5% del valor asegurado",
        "valor_asegurado": "$60.000.000",
        "emitida_en": "2026-11-08T15:01:00Z",
    }


def test_el_detalle_de_una_poliza_ajena_es_404(con_sesion, nucleo, canal):
    nucleo.responder("GET", f"{NUCLEO}/POL-9", problema(404, "recurso_no_encontrado"))

    respuesta = con_sesion.get(f"{canal.prefijo}/polizas/POL-9")

    assert respuesta.status_code == 404
    assert respuesta.json()["codigo"] == "poliza_no_encontrada"


def test_un_numero_con_forma_de_ruta_no_llega_al_nucleo(con_sesion, nucleo, canal):
    respuesta = con_sesion.get(f"{canal.prefijo}/polizas/..%2Fx")

    assert respuesta.status_code == 404
    assert al_nucleo(nucleo) == []


def test_el_documento_se_descarga_en_los_dos_canales(con_sesion, nucleo, canal):
    nucleo.responder(
        "GET",
        f"{NUCLEO}/POL-1/documento",
        lambda _: httpx.Response(
            200,
            content=b"%PDF-1.4",
            headers={
                "content-type": "application/pdf",
                "content-disposition": 'attachment; filename="POL-1.pdf"',
            },
        ),
    )

    respuesta = con_sesion.get(f"{canal.prefijo}/polizas/POL-1/documento")

    assert respuesta.status_code == 200
    assert respuesta.content == b"%PDF-1.4"
    assert respuesta.headers["content-type"] == "application/pdf"
    assert respuesta.headers["content-disposition"] == 'attachment; filename="POL-1.pdf"'
    assert respuesta.headers["x-content-type-options"] == "nosniff"


def test_el_documento_de_una_poliza_ajena_es_404(con_sesion, nucleo, canal):
    nucleo.responder("GET", f"{NUCLEO}/POL-9/documento", problema(404, "poliza_no_encontrada"))

    respuesta = con_sesion.get(f"{canal.prefijo}/polizas/POL-9/documento")

    assert respuesta.status_code == 404
    assert respuesta.json()["codigo"] == "poliza_no_encontrada"


def test_una_poliza_de_prueba_no_tiene_documento(con_sesion, nucleo, canal):
    nucleo.responder("GET", f"{NUCLEO}/POL-STG/documento", problema(404, "documento_no_disponible"))

    respuesta = con_sesion.get(f"{canal.prefijo}/polizas/POL-STG/documento")

    assert respuesta.status_code == 404
    assert respuesta.json()["codigo"] == "documento_no_disponible"


def test_una_prima_sin_moneda_se_asume_en_pesos_y_queda_en_el_log(
    con_sesion, nucleo, canal, caplog
):
    nucleo.responder("GET", NUCLEO, 200, [{**EMITIDA, "moneda": None}])

    with caplog.at_level("WARNING", logger="app.comun.polizas"):
        respuesta = con_sesion.get(f"{canal.prefijo}/polizas")

    assert respuesta.json()[0]["prima_mensual"] == {"valor": 132900, "moneda": "COP"}
    assert "sin moneda" in caplog.text
