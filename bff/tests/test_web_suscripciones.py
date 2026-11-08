"""Pruebas del checkout del router web (WEB-F03) contra un núcleo falso.

Comprar desde la app es MOB-F04, fuera del MVP: estas rutas solo existen en la web.
"""

from __future__ import annotations

import uuid

import pytest

from .conftest import CLIENTE, WEB, al_nucleo, problema

NUCLEO = f"/v1/suscripcion/clientes/{CLIENTE}/suscripciones"
PREFIJO = "/api/v1/web"
CLAVE = str(uuid.uuid4())

PAGO_DEL_NUCLEO = {
    "id": "PAG-1",
    "concepto": "SUS-1",
    "estado": "aprobado",
    "medio": "tarjeta_credito",
    "monto": 132900,
    "moneda": "COP",
    "referencia": "TX-77120418",
    "creado_en": "2026-11-08T15:00:00Z",
    "actualizado_en": "2026-11-08T15:00:01Z",
    "reintento": False,
}
PAGO_PARA_LA_WEB = {
    "id": "PAG-1",
    "estado": "aprobado",
    "medio": "tarjeta_credito",
    "monto": {"valor": 132900, "moneda": "COP"},
    "referencia": "TX-77120418",
    "creado_en": "2026-11-08T15:00:00Z",
    "reintento": False,
}
SUSCRIPCION_DEL_NUCLEO = {
    "id": "SUS-1",
    "id_cotizacion": "COT-1",
    "id_cliente": CLIENTE,
    "producto": "auto",
    "nombre_producto": "Seguro de Auto Todo Riesgo",
    "prima_mensual": 132900,
    "moneda": "COP",
    "valor_asegurado": "$60.000.000",
    "deducible": "5% del valor asegurado",
    "coberturas": [],
    "estado": "aceptada",
    "canal": "web",
    "aceptada_en": "2026-11-08T15:00:00Z",
    "pagada_en": None,
    "firmada_en": None,
    "id_firma": None,
    "pago": None,
    "poliza": None,
    "trazabilidad": [],
}
SUSCRIPCION_PARA_LA_WEB = {
    "id": "SUS-1",
    "cotizacion_id": "COT-1",
    "nombre_producto": "Seguro de Auto Todo Riesgo",
    "prima_mensual": {"valor": 132900, "moneda": "COP"},
    "estado": "aceptada",
    "aceptada_en": "2026-11-08T15:00:00Z",
    "pago": None,
    "poliza": None,
    "firmada_en": None,
}
EMITIDA_DEL_NUCLEO = {
    **SUSCRIPCION_DEL_NUCLEO,
    "estado": "emitida",
    "pago": PAGO_DEL_NUCLEO,
    "firmada_en": "2026-11-08T15:01:00Z",
    "id_firma": "FIR-1",
    "poliza": {"numero": "POL-2026-000001", "inicio": "2026-11-08", "fin": "2027-11-07"},
}


@pytest.fixture
def canal():
    return WEB


@pytest.mark.parametrize(
    ("metodo", "ruta"),
    [
        ("POST", "/suscripciones"),
        ("GET", "/suscripciones/SUS-1"),
        ("POST", "/suscripciones/SUS-1/pagos"),
        ("GET", "/suscripciones/SUS-1/pagos/PAG-1"),
        ("POST", "/suscripciones/SUS-1/firma"),
        ("GET", "/polizas/POL-1/documento"),
    ],
)
def test_sin_sesion_toda_ruta_es_401(cliente, nucleo, metodo, ruta):
    respuesta = cliente.request(metodo, f"{PREFIJO}{ruta}", json={})

    assert respuesta.status_code == 401
    assert al_nucleo(nucleo) == []


def test_el_checkout_no_existe_en_la_app(con_sesion):
    assert con_sesion.post("/api/v1/movil/suscripciones", json={}).status_code == 404


def test_aceptar_traduce_al_contrato_de_la_web(con_sesion, nucleo):
    nucleo.responder("POST", NUCLEO, 201, SUSCRIPCION_DEL_NUCLEO)

    respuesta = con_sesion.post(f"{PREFIJO}/suscripciones", json={"cotizacion_id": "COT-1"})

    assert respuesta.status_code == 201
    assert respuesta.json() == SUSCRIPCION_PARA_LA_WEB
    assert nucleo.cuerpo_de(nucleo.ultima) == {"id_cotizacion": "COT-1"}
    assert nucleo.ultima.headers["X-Canal"] == "web"


def test_aceptar_otra_vez_responde_200(con_sesion, nucleo):
    nucleo.responder("POST", NUCLEO, 200, SUSCRIPCION_DEL_NUCLEO)

    respuesta = con_sesion.post(f"{PREFIJO}/suscripciones", json={"cotizacion_id": "COT-1"})

    assert respuesta.status_code == 200


@pytest.mark.parametrize(
    ("estado", "codigo"), [(409, "cotizacion_vencida"), (404, "cotizacion_no_encontrada")]
)
def test_aceptar_una_cotizacion_vencida_o_ajena(con_sesion, nucleo, estado, codigo):
    nucleo.responder("POST", NUCLEO, problema(estado, codigo))

    respuesta = con_sesion.post(f"{PREFIJO}/suscripciones", json={"cotizacion_id": "COT-1"})

    assert respuesta.status_code == estado
    assert respuesta.json()["codigo"] == codigo


def test_aceptar_un_id_con_forma_de_ruta_no_llega_al_nucleo(con_sesion, nucleo):
    respuesta = con_sesion.post(f"{PREFIJO}/suscripciones", json={"cotizacion_id": "../x"})

    assert respuesta.status_code == 404
    assert respuesta.json()["codigo"] == "cotizacion_no_encontrada"
    assert al_nucleo(nucleo) == []


def test_consultar_trae_la_poliza_con_su_documento(con_sesion, nucleo):
    nucleo.responder("GET", f"{NUCLEO}/SUS-1", 200, EMITIDA_DEL_NUCLEO)

    cuerpo = con_sesion.get(f"{PREFIJO}/suscripciones/SUS-1").json()

    assert cuerpo["estado"] == "emitida"
    assert cuerpo["pago"] == PAGO_PARA_LA_WEB
    assert cuerpo["poliza"] == {
        "numero": "POL-2026-000001",
        "inicio": "2026-11-08",
        "fin": "2027-11-07",
        "documento_url": "/api/v1/web/polizas/POL-2026-000001/documento",
    }


def test_una_suscripcion_ajena_es_404(con_sesion, nucleo):
    nucleo.responder("GET", f"{NUCLEO}/SUS-9", problema(404, "suscripcion_no_encontrada"))

    respuesta = con_sesion.get(f"{PREFIJO}/suscripciones/SUS-9")

    assert respuesta.status_code == 404
    assert respuesta.json()["codigo"] == "suscripcion_no_encontrada"


def test_pagar_pasa_la_clave_y_el_canal(con_sesion, nucleo):
    nucleo.responder("POST", f"{NUCLEO}/SUS-1/pagos", 201, PAGO_DEL_NUCLEO)

    respuesta = con_sesion.post(
        f"{PREFIJO}/suscripciones/SUS-1/pagos",
        json={"medio": "tarjeta_credito"},
        headers={"Idempotency-Key": CLAVE},
    )

    assert respuesta.status_code == 201
    assert respuesta.json() == PAGO_PARA_LA_WEB
    assert nucleo.ultima.headers["Idempotency-Key"] == CLAVE
    assert nucleo.ultima.headers["X-Canal"] == "web"
    assert nucleo.cuerpo_de(nucleo.ultima) == {"medio": "tarjeta_credito"}


def test_reintentar_el_pago_responde_200_con_reintento(con_sesion, nucleo):
    nucleo.responder("POST", f"{NUCLEO}/SUS-1/pagos", 200, {**PAGO_DEL_NUCLEO, "reintento": True})

    respuesta = con_sesion.post(
        f"{PREFIJO}/suscripciones/SUS-1/pagos",
        json={"medio": "tarjeta_credito"},
        headers={"Idempotency-Key": CLAVE},
    )

    assert respuesta.status_code == 200
    assert respuesta.json()["reintento"] is True


@pytest.mark.parametrize("codigo", ["pago_en_curso", "pago_ya_registrado"])
def test_los_conflictos_del_pago_llegan_con_su_codigo(con_sesion, nucleo, codigo):
    nucleo.responder("POST", f"{NUCLEO}/SUS-1/pagos", problema(409, codigo))

    respuesta = con_sesion.post(
        f"{PREFIJO}/suscripciones/SUS-1/pagos",
        json={"medio": "pse"},
        headers={"Idempotency-Key": CLAVE},
    )

    assert respuesta.status_code == 409
    assert respuesta.json()["codigo"] == codigo


@pytest.mark.parametrize("clave", [None, "no-es-uuid"])
def test_pagar_sin_una_clave_uuid_es_422(con_sesion, nucleo, clave):
    encabezados = {"Idempotency-Key": clave} if clave else {}

    respuesta = con_sesion.post(
        f"{PREFIJO}/suscripciones/SUS-1/pagos", json={"medio": "pse"}, headers=encabezados
    )

    assert respuesta.status_code == 422
    assert respuesta.json()["codigo"] == "clave_idempotencia_invalida"
    assert al_nucleo(nucleo) == []


def test_pagar_con_un_medio_desconocido_es_422(con_sesion, nucleo):
    respuesta = con_sesion.post(
        f"{PREFIJO}/suscripciones/SUS-1/pagos",
        json={"medio": "efectivo"},
        headers={"Idempotency-Key": CLAVE},
    )

    assert respuesta.status_code == 422
    assert al_nucleo(nucleo) == []


def test_la_pasarela_caida_es_503(con_sesion, nucleo):
    nucleo.responder("POST", f"{NUCLEO}/SUS-1/pagos", problema(503, "dependencia_no_disponible"))

    respuesta = con_sesion.post(
        f"{PREFIJO}/suscripciones/SUS-1/pagos",
        json={"medio": "pse"},
        headers={"Idempotency-Key": CLAVE},
    )

    assert respuesta.status_code == 503


def test_consultar_un_pago_pendiente(con_sesion, nucleo):
    nucleo.responder("GET", f"{NUCLEO}/SUS-1/pagos/PAG-1", 200, PAGO_DEL_NUCLEO)

    respuesta = con_sesion.get(f"{PREFIJO}/suscripciones/SUS-1/pagos/PAG-1")

    assert respuesta.json() == PAGO_PARA_LA_WEB


def test_un_pago_inexistente_es_pago_no_encontrado(con_sesion, nucleo):
    nucleo.responder("GET", f"{NUCLEO}/SUS-1/pagos/PAG-9", problema(404, "recurso_no_encontrado"))

    respuesta = con_sesion.get(f"{PREFIJO}/suscripciones/SUS-1/pagos/PAG-9")

    assert respuesta.status_code == 404
    assert respuesta.json()["codigo"] == "pago_no_encontrado"


def test_firmar_emite_la_poliza(con_sesion, nucleo):
    nucleo.responder("POST", f"{NUCLEO}/SUS-1/firma", 201, EMITIDA_DEL_NUCLEO)

    respuesta = con_sesion.post(
        f"{PREFIJO}/suscripciones/SUS-1/firma", json={"acepta_condiciones": True}
    )

    assert respuesta.status_code == 201
    assert respuesta.json()["estado"] == "emitida"
    assert respuesta.json()["poliza"]["numero"] == "POL-2026-000001"
    assert nucleo.cuerpo_de(nucleo.ultima) == {"acepta_condiciones": True}


@pytest.mark.parametrize(
    ("estado", "codigo"), [(409, "pago_no_aprobado"), (422, "aceptacion_requerida")]
)
def test_firmar_sin_pago_o_sin_aceptar(con_sesion, nucleo, estado, codigo):
    nucleo.responder("POST", f"{NUCLEO}/SUS-1/firma", problema(estado, codigo))

    respuesta = con_sesion.post(
        f"{PREFIJO}/suscripciones/SUS-1/firma", json={"acepta_condiciones": True}
    )

    assert respuesta.status_code == estado
    assert respuesta.json()["codigo"] == codigo


def test_el_documento_de_la_poliza_se_descarga_como_pdf(con_sesion, nucleo):
    import httpx

    nucleo.responder(
        "GET",
        f"/v1/polizas/clientes/{CLIENTE}/polizas/POL-1/documento",
        lambda _: httpx.Response(
            200,
            content=b"%PDF-1.4",
            headers={
                "content-type": "application/pdf",
                "content-disposition": 'attachment; filename="POL-1.pdf"',
            },
        ),
    )

    respuesta = con_sesion.get(f"{PREFIJO}/polizas/POL-1/documento")

    assert respuesta.status_code == 200
    assert respuesta.content == b"%PDF-1.4"
    assert respuesta.headers["content-type"] == "application/pdf"
    assert respuesta.headers["content-disposition"] == 'attachment; filename="POL-1.pdf"'


def test_el_documento_de_una_poliza_ajena_es_404(con_sesion, nucleo):
    nucleo.responder(
        "GET",
        f"/v1/polizas/clientes/{CLIENTE}/polizas/POL-9/documento",
        problema(404, "poliza_no_encontrada"),
    )

    respuesta = con_sesion.get(f"{PREFIJO}/polizas/POL-9/documento")

    assert respuesta.status_code == 404
    assert respuesta.json()["codigo"] == "poliza_no_encontrada"
