"""Pruebas del seguimiento del siniestro en los dos routers (WEB-F06).

El estado, el historial y los requerimientos son los mismos en la web y en la app
(criterio 1): cada prueba corre en los dos canales.
"""

from __future__ import annotations

import pytest

from .conftest import CLIENTE, problema
from .test_siniestros import DEL_NUCLEO, PARA_EL_CANAL

NUCLEO = f"/v1/siniestros/clientes/{CLIENTE}/siniestros"
FOTO = b"\xff\xd8\xff\xe0" + b"foto" * 100

HISTORIAL = [
    {
        "id": "EVS-SIN-1-reporte",
        "estado_anterior": None,
        "estado": "reportado",
        "ocurrido_en": "2026-10-26T15:00:00Z",
        "motivo": None,
    },
    {
        "id": "EVS-2",
        "estado_anterior": "reportado",
        "estado": "requiere_informacion",
        "ocurrido_en": "2026-10-27T09:00:00Z",
        "motivo": "Falta soporte",
    },
]
REQUERIMIENTOS = [
    {
        "id": "REQ-1",
        "descripcion": "Factura de la reparación",
        "creado_en": "2026-10-27T09:00:00Z",
        "pendiente": True,
        "atendido_en": None,
        "id_evidencia": None,
    }
]
EN_SEGUIMIENTO = {
    **DEL_NUCLEO,
    "estado": "requiere_informacion",
    "siguiente_paso": "adjuntar_evidencia",
    "estado_actualizado_en": "2026-10-27T09:00:00Z",
    "historial": HISTORIAL,
    "requerimientos": REQUERIMIENTOS,
}


def test_la_lista_trae_el_estado_actual_y_cuando_cambio(con_sesion, nucleo, canal):
    """Criterios 1 y 2: refrescar la lista muestra el estado nuevo."""
    nucleo.responder(
        "GET",
        NUCLEO,
        200,
        [
            {
                **DEL_NUCLEO,
                "estado": "en_evaluacion",
                "estado_actualizado_en": "2026-10-27T09:00:00Z",
            }
        ],
    )

    [siniestro] = con_sesion.get(f"{canal.prefijo}/siniestros").json()

    assert siniestro["estado"] == "en_evaluacion"
    assert siniestro["estado_actualizado_en"] == "2026-10-27T09:00:00Z"
    assert "historial" not in siniestro


def test_el_detalle_trae_historial_y_requerimientos(con_sesion, nucleo, canal):
    """Criterios 4 y 5."""
    nucleo.responder("GET", f"{NUCLEO}/SIN-1", 200, EN_SEGUIMIENTO)

    detalle = con_sesion.get(f"{canal.prefijo}/siniestros/SIN-1").json()

    assert detalle == {
        **PARA_EL_CANAL,
        "estado": "requiere_informacion",
        "siguiente_paso": "adjuntar_evidencia",
        "estado_actualizado_en": "2026-10-27T09:00:00Z",
        "historial": HISTORIAL,
        "requerimientos": REQUERIMIENTOS,
    }


def test_un_nucleo_sin_seguimiento_da_listas_vacias(con_sesion, nucleo, canal):
    """Un núcleo anterior a WEB-F06 no trae los campos nuevos: el canal ve listas vacías."""
    nucleo.responder("GET", f"{NUCLEO}/SIN-1", 200, DEL_NUCLEO)

    detalle = con_sesion.get(f"{canal.prefijo}/siniestros/SIN-1").json()

    assert detalle["historial"] == [] and detalle["requerimientos"] == []
    assert detalle["estado_actualizado_en"] is None


def test_la_evidencia_responde_un_requerimiento(con_sesion, nucleo, canal):
    nucleo.responder(
        "POST",
        f"{NUCLEO}/SIN-1/evidencias",
        201,
        {
            "id": "EVI-1",
            "tipo_contenido": "image/jpeg",
            "tamano": len(FOTO),
            "nombre_original": "factura.jpg",
            "canal": canal.nombre,
            "cargada_en": "2026-10-27T10:00:00Z",
        },
    )

    respuesta = con_sesion.post(
        f"{canal.prefijo}/siniestros/SIN-1/evidencias",
        files={"archivo": ("factura.jpg", FOTO, "image/jpeg")},
        data={"id_requerimiento": "REQ-1"},
    )

    assert respuesta.status_code == 201, respuesta.text
    assert nucleo.ultima.url.params["id_requerimiento"] == "REQ-1"


def test_sin_requerimiento_la_evidencia_no_lo_manda(con_sesion, nucleo, canal):
    nucleo.responder(
        "POST",
        f"{NUCLEO}/SIN-1/evidencias",
        201,
        {
            "id": "EVI-1",
            "tipo_contenido": "image/jpeg",
            "tamano": len(FOTO),
            "nombre_original": "foto.jpg",
            "canal": canal.nombre,
            "cargada_en": "2026-10-27T10:00:00Z",
        },
    )

    con_sesion.post(
        f"{canal.prefijo}/siniestros/SIN-1/evidencias",
        files={"archivo": ("foto.jpg", FOTO, "image/jpeg")},
    )

    assert "id_requerimiento" not in nucleo.ultima.url.params


@pytest.mark.parametrize(
    ("estado", "codigo"),
    [
        (404, "requerimiento_no_encontrado"),
        (404, "siniestro_no_encontrado"),
        (409, "requerimiento_ya_atendido"),
    ],
)
def test_responder_un_requerimiento_conserva_el_codigo_del_nucleo(
    con_sesion, nucleo, canal, estado, codigo
):
    """Un 404 por el requerimiento no se confunde con uno por el siniestro: los dos
    contratos documentan `requerimiento_no_encontrado`."""
    nucleo.responder("POST", f"{NUCLEO}/SIN-1/evidencias", problema(estado, codigo, "no"))

    respuesta = con_sesion.post(
        f"{canal.prefijo}/siniestros/SIN-1/evidencias",
        files={"archivo": ("factura.jpg", FOTO, "image/jpeg")},
        data={"id_requerimiento": "REQ-NO"},
    )

    assert (respuesta.status_code, respuesta.json()["codigo"]) == (estado, codigo)


def test_un_404_generico_al_adjuntar_sigue_siendo_del_siniestro(con_sesion, nucleo, canal):
    nucleo.responder(
        "POST", f"{NUCLEO}/SIN-1/evidencias", problema(404, "recurso_no_encontrado", "no")
    )

    respuesta = con_sesion.post(
        f"{canal.prefijo}/siniestros/SIN-1/evidencias",
        files={"archivo": ("factura.jpg", FOTO, "image/jpeg")},
        data={"id_requerimiento": "REQ-1"},
    )

    assert (respuesta.status_code, respuesta.json()["codigo"]) == (404, "siniestro_no_encontrado")
