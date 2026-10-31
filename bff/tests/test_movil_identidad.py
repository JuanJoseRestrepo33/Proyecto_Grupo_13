"""Pruebas de la verificación de identidad del canal móvil (MOB-F01, WEB-F13)."""

from __future__ import annotations

import pytest

from .conftest import CLIENTE, problema

RUTA = "/api/v1/movil/identidad/verificacion"
NUCLEO = f"/v1/identidad/clientes/{CLIENTE}/verificaciones"
VERIFICACION = {
    "id": "KYC-1",
    "estado": "aprobada",
    "motivo": None,
    "reintento_permitido": None,
    "actualizada_en": "2026-10-26T15:00:00Z",
    "solicitada_en": "2026-10-26T14:59:00Z",
    "proveedor": "kyc-simulado",
}
APROBADA = {"id": "KYC-1", "estado": "aprobada", "actualizada_en": "2026-10-26T15:00:00Z"}
RECHAZO = {"motivo": "datos_no_coinciden", "reintento_permitido": True}


@pytest.fixture
def con_sesion(cliente, firmante):
    cliente.headers["Authorization"] = f"Bearer {firmante.emitir(canal='movil')}"
    return cliente


def _al_nucleo(nucleo) -> list:
    return [s for s in nucleo.solicitudes if s.url.path != "/salud"]


@pytest.mark.parametrize("metodo", ["GET", "POST"])
def test_sin_sesion_es_401_sin_tocar_el_nucleo(cliente, nucleo, metodo):
    respuesta = cliente.request(metodo, RUTA)

    assert respuesta.status_code == 401
    assert respuesta.json()["codigo"] == "sesion_no_vigente"
    assert respuesta.headers["WWW-Authenticate"] == "Bearer"
    assert _al_nucleo(nucleo) == []


def test_consultar_responde_la_forma_del_contrato_sin_datos_internos(con_sesion, nucleo):
    nucleo.responder("GET", f"{NUCLEO}/ultima", 200, VERIFICACION)

    respuesta = con_sesion.get(RUTA)

    assert respuesta.status_code == 200
    assert respuesta.json() == APROBADA


def test_un_rechazo_trae_su_motivo_y_si_admite_reintento(con_sesion, nucleo):
    nucleo.responder(
        "GET", f"{NUCLEO}/ultima", 200, {**VERIFICACION, "estado": "rechazada", **RECHAZO}
    )

    respuesta = con_sesion.get(RUTA)

    assert respuesta.json() == {**APROBADA, "estado": "rechazada", **RECHAZO}


def test_sin_verificaciones_es_el_404_del_contrato(con_sesion, nucleo):
    nucleo.responder("GET", f"{NUCLEO}/ultima", problema(404, "recurso_no_encontrado"))

    respuesta = con_sesion.get(RUTA)

    assert respuesta.status_code == 404
    assert respuesta.json()["codigo"] == "verificacion_no_encontrada"


def test_solicitar_es_202_y_no_envia_el_documento(con_sesion, nucleo):
    nucleo.responder("POST", NUCLEO, 202, VERIFICACION)

    respuesta = con_sesion.post(RUTA)

    assert respuesta.status_code == 202
    assert respuesta.json() == APROBADA
    assert nucleo.ultima.content == b""
    assert nucleo.ultima.headers["X-Canal"] == "movil"


@pytest.mark.parametrize("codigo", ["identidad_ya_verificada", "verificacion_en_curso"])
def test_los_conflictos_llegan_con_su_codigo(con_sesion, nucleo, codigo):
    nucleo.responder("POST", NUCLEO, problema(409, codigo))

    respuesta = con_sesion.post(RUTA)

    assert respuesta.status_code == 409
    assert respuesta.json()["codigo"] == codigo


def test_si_la_cuenta_ya_no_existe_la_app_vuelve_a_iniciar_sesion(con_sesion, nucleo):
    nucleo.responder("POST", NUCLEO, problema(404, "cliente_no_encontrado"))

    respuesta = con_sesion.post(RUTA)

    assert respuesta.status_code == 401
    assert respuesta.json()["codigo"] == "sesion_no_vigente"
    assert respuesta.headers["WWW-Authenticate"] == "Bearer"


def test_el_proveedor_caido_no_se_disfraza(con_sesion, nucleo):
    nucleo.responder("POST", NUCLEO, problema(503, "proveedor_kyc_no_disponible"))

    respuesta = con_sesion.post(RUTA)

    assert respuesta.status_code == 503
    assert respuesta.json()["codigo"] == "proveedor_kyc_no_disponible"
