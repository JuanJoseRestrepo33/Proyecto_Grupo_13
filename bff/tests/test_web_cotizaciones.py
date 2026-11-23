"""Pruebas de las rutas de cotización del router web contra un núcleo falso.

Las respuestas del núcleo falso tienen la forma de `CotizacionDto` y `ProductoDto` de la API
`/v1/cotizacion`; las afirmaciones, la del contrato `bff-web`.
"""

from __future__ import annotations

import pytest

from app.web.dependencias import COOKIE_SESION

from .conftest import CLIENTE, al_nucleo, problema

RUTA = "/api/v1/web/cotizaciones"
NUCLEO = "/v1/cotizacion"

SOLICITUD = {
    "producto": "auto",
    "asegurado": {"edad": 34, "ciudad": "Bogotá", "valor_asegurado": 60000000},
    "usar_open_finance": True,
}

PRODUCTO = {
    "id": "auto",
    "nombre": "Seguro de Auto — Plan Full",
    "nombre_corto": "Auto",
    "prima_estandar": 148000,
    "moneda": "COP",
    "valor_asegurado": "$60.000.000 COP",
    "deducible": "5% del valor asegurado",
    "coberturas": [{"nombre": "Responsabilidad civil", "limite": "$1.200.000.000 COP"}],
}


def cotizacion_del_nucleo(**cambios) -> dict:
    oferta = {
        "prima_mensual": 133200,
        "prima_estandar": 148000,
        "ahorro_mensual": 14800,
        "moneda": "COP",
        "valor_asegurado": "$60.000.000 COP",
        "deducible": "5% del valor asegurado",
        "coberturas": [{"nombre": "Responsabilidad civil", "limite": "$1.200.000.000 COP"}],
        "base_perfilamiento": "open_finance",
        "uso_open_finance": True,
        "version_regla": "rating-v3.2",
        "factores": [
            {"nombre": "Comportamiento de pago", "efecto": -0.06, "efecto_porcentual": "-6%"}
        ],
    }
    oferta.update(cambios.pop("oferta", {}))
    cuerpo = {
        "id_cotizacion": "QT-2026-08831",
        "producto": "auto",
        "nombre_producto": "Seguro de Auto — Plan Full",
        "estado": "vigente",
        "creada_en": "2026-10-26T15:00:00Z",
        "expira_en": "2026-10-26T15:05:00Z",
        "segundos_restantes": 300,
        "oferta": oferta,
        "id_consentimiento": "CONSENT-1",
        "id_cotizacion_origen": None,
        "id_cliente": CLIENTE,
        "motivo_tarifa": None,
    }
    cuerpo.update(cambios)
    return cuerpo


@pytest.fixture
def web(cliente, firmante):
    cliente.cookies.set(COOKIE_SESION, firmante.emitir(canal="web"))
    return cliente


def test_los_productos_llegan_con_el_formato_de_la_web(web, nucleo):
    nucleo.responder("GET", f"{NUCLEO}/productos", 200, [PRODUCTO])

    productos = web.get(f"{RUTA}/productos").json()

    assert productos == [
        {
            "codigo": "auto",
            "nombre": "Seguro de Auto — Plan Full",
            "nombre_corto": "Auto",
            "descripcion": "Responsabilidad civil, todo riesgo y hurto.",
            "prima_estandar": {"valor": 148000, "moneda": "COP"},
            "valor_asegurado": "$60.000.000 COP",
            "deducible": "5% del valor asegurado",
            "coberturas": PRODUCTO["coberturas"],
        }
    ]


@pytest.mark.parametrize(
    ("metodo", "ruta"),
    [
        ("GET", f"{RUTA}/productos"),
        ("POST", RUTA),
        ("GET", f"{RUTA}/QT-2026-08831"),
        ("POST", f"{RUTA}/QT-2026-08831/recotizacion"),
    ],
)
def test_sin_sesion_toda_ruta_responde_401(cliente, nucleo, metodo, ruta):
    respuesta = cliente.request(metodo, ruta, json=SOLICITUD)

    assert respuesta.status_code == 401
    assert respuesta.json()["codigo"] == "sesion_no_vigente"
    assert al_nucleo(nucleo) == []


def test_cotizar_usa_el_cliente_de_la_sesion_y_el_canal_web(web, nucleo):
    nucleo.responder("POST", f"{NUCLEO}/cotizaciones", 201, cotizacion_del_nucleo())

    respuesta = web.post(RUTA, json={**SOLICITUD, "id_cliente": "CLI-OTRO"})

    assert respuesta.status_code == 201
    enviado = nucleo.cuerpo_de(nucleo.ultima)
    assert enviado["id_cliente"] == CLIENTE
    assert enviado["usar_open_finance"] is True
    assert enviado["asegurado"] == SOLICITUD["asegurado"]
    assert nucleo.ultima.headers["X-Canal"] == "web"


def test_una_tarifa_personalizada_llega_completa(web, nucleo):
    nucleo.responder("POST", f"{NUCLEO}/cotizaciones", 201, cotizacion_del_nucleo())

    cuerpo = web.post(RUTA, json=SOLICITUD).json()

    assert cuerpo["id"] == "QT-2026-08831"
    assert cuerpo["tipo_tarifa"] == "personalizada"
    assert cuerpo["motivo_tarifa"] is None
    assert cuerpo["prima_mensual"] == {"valor": 133200, "moneda": "COP"}
    assert cuerpo["ahorro_mensual"] == {"valor": 14800, "moneda": "COP"}
    assert cuerpo["factores"] == [{"nombre": "Comportamiento de pago", "efecto": -0.06}]
    assert cuerpo["vigente_hasta"] == "2026-10-26T15:05:00Z"
    assert cuerpo["version_regla"] == "rating-v3.2"


@pytest.mark.parametrize(
    "motivo", ["sin_consentimiento", "open_finance_desactivado", "proveedor_no_disponible"]
)
def test_una_tarifa_estandar_dice_su_motivo(web, nucleo, motivo):
    estandar = cotizacion_del_nucleo(
        motivo_tarifa=motivo,
        oferta={"uso_open_finance": False, "base_perfilamiento": "estandar"},
    )
    nucleo.responder("POST", f"{NUCLEO}/cotizaciones", 201, estandar)

    cuerpo = web.post(RUTA, json=SOLICITUD).json()

    assert cuerpo["tipo_tarifa"] == "estandar"
    assert cuerpo["motivo_tarifa"] == motivo


def test_con_el_perfil_en_cache_la_tarifa_sigue_siendo_personalizada(web, nucleo):
    """Criterio 3: la interfaz lo ve en `base_perfilamiento`, no como tarifa estándar."""
    degradada = cotizacion_del_nucleo(oferta={"base_perfilamiento": "open_finance_en_cache"})
    nucleo.responder("POST", f"{NUCLEO}/cotizaciones", 201, degradada)

    cuerpo = web.post(RUTA, json=SOLICITUD).json()

    assert cuerpo["tipo_tarifa"] == "personalizada"
    assert cuerpo["base_perfilamiento"] == "open_finance_en_cache"


@pytest.mark.parametrize(
    ("cambio", "codigo"),
    [
        ({"producto": "moto"}, "producto_invalido"),
        ({"asegurado": {"edad": 12, "ciudad": "Bogotá"}}, "asegurado_invalido"),
        ({"asegurado": {"edad": 34, "ciudad": ""}}, "asegurado_invalido"),
        ({"usar_open_finance": None}, "solicitud_invalida"),
    ],
)
def test_datos_invalidos_no_lleganal_nucleo(web, nucleo, cambio, codigo):
    respuesta = web.post(RUTA, json={**SOLICITUD, **cambio})

    assert respuesta.status_code == 422
    assert respuesta.json()["codigo"] == codigo
    assert al_nucleo(nucleo) == []


def test_consultar_una_cotizacion_propia(web, nucleo):
    nucleo.responder("GET", f"{NUCLEO}/cotizaciones/QT-2026-08831", 200, cotizacion_del_nucleo())

    respuesta = web.get(f"{RUTA}/QT-2026-08831")

    assert respuesta.status_code == 200
    assert respuesta.json()["id"] == "QT-2026-08831"


def test_la_cotizacion_de_otro_cliente_es_404_como_si_no_existiera(web, nucleo):
    ajena = cotizacion_del_nucleo(id_cliente="CLI-OTRO")
    nucleo.responder("GET", f"{NUCLEO}/cotizaciones/QT-2026-08831", 200, ajena)

    respuesta = web.get(f"{RUTA}/QT-2026-08831")

    assert respuesta.status_code == 404
    assert respuesta.json()["codigo"] == "cotizacion_no_encontrada"


def test_una_cotizacion_inexistente_usa_el_codigo_del_contrato(web):
    respuesta = web.get(f"{RUTA}/QT-2026-99999")

    assert respuesta.status_code == 404
    assert respuesta.json()["codigo"] == "cotizacion_no_encontrada"


def test_recotizar_una_propia_crea_otra(web, nucleo):
    nucleo.responder("GET", f"{NUCLEO}/cotizaciones/QT-2026-08831", 200, cotizacion_del_nucleo())
    nueva = cotizacion_del_nucleo(id_cotizacion="QT-2026-08832")
    nucleo.responder("POST", f"{NUCLEO}/cotizaciones/QT-2026-08831/recotizar", 201, nueva)

    respuesta = web.post(f"{RUTA}/QT-2026-08831/recotizacion")

    assert respuesta.status_code == 201
    assert respuesta.json()["id"] == "QT-2026-08832"


def test_no_se_recotiza_la_cotizacion_de_otro(web, nucleo):
    ajena = cotizacion_del_nucleo(id_cliente="CLI-OTRO")
    nucleo.responder("GET", f"{NUCLEO}/cotizaciones/QT-2026-08831", 200, ajena)

    respuesta = web.post(f"{RUTA}/QT-2026-08831/recotizacion")

    assert respuesta.status_code == 404
    assert [s.method for s in al_nucleo(nucleo)] == ["GET"]


def test_el_nucleo_caido_es_503_para_la_web(web, nucleo):
    nucleo.responder(
        "POST", f"{NUCLEO}/cotizaciones", problema(503, "dependencia_no_disponible", "caído")
    )

    respuesta = web.post(RUTA, json=SOLICITUD)

    assert respuesta.status_code == 503
    assert respuesta.json()["codigo"] == "dependencia_no_disponible"


@pytest.mark.parametrize("identificador", ["QT..2026", "QT.2026", "QT 2026", "a" * 129])
def test_un_identificador_que_no_es_un_segmento_seguro_no_llegaal_nucleo(
    web, nucleo, identificador
):
    """Un `..` no puede llevar la solicitud a otra ruta del núcleo."""
    for respuesta in (
        web.get(f"{RUTA}/{identificador}"),
        web.post(f"{RUTA}/{identificador}/recotizacion"),
    ):
        assert respuesta.status_code == 404
        assert respuesta.json()["codigo"] == "cotizacion_no_encontrada"
    assert al_nucleo(nucleo) == []
