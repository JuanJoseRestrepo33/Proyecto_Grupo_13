"""Pruebas del registro del dispositivo y las notificaciones del router móvil (MOB-F06)."""

from __future__ import annotations

import pytest

from .conftest import CLIENTE, MOVIL, al_nucleo, problema

NUCLEO = f"/v1/notificaciones/clientes/{CLIENTE}"
PREFIJO = "/api/v1/movil"
DISPOSITIVO = {
    "id": "DIS-1",
    "plataforma": "android",
    "registrado_en": "2026-11-08T15:00:00Z",
    "actualizado_en": "2026-11-08T15:00:00Z",
}
NOTIFICACION = {
    "id": "NOT-1",
    "titulo": "Tu siniestro SIN-2026-000001 cambió",
    "cuerpo": "Ya estamos evaluando tu caso.",
    "datos": {
        "tipo": "siniestro_estado",
        "id_siniestro": "SIN-1",
        "referencia": "SIN-2026-000001",
        "estado": "en_evaluacion",
    },
    "creada_en": "2026-11-08T15:00:00Z",
    "estado": "enviada",
    "leida": False,
}


@pytest.fixture
def canal():
    return MOVIL


@pytest.mark.parametrize(
    ("metodo", "ruta"),
    [
        ("POST", "/dispositivos"),
        ("DELETE", "/dispositivos/DIS-1"),
        ("GET", "/notificaciones"),
        ("POST", "/notificaciones/NOT-1/lectura"),
    ],
)
def test_sin_sesion_toda_ruta_es_401(cliente, nucleo, metodo, ruta):
    respuesta = cliente.request(metodo, f"{PREFIJO}{ruta}", json={"token_push": "t"})

    assert respuesta.status_code == 401
    assert al_nucleo(nucleo) == []


def test_las_rutas_no_existen_en_la_web(cliente, firmante):
    cliente.cookies.set("sesion", firmante.emitir(canal="web"))

    assert cliente.get("/api/v1/web/notificaciones").status_code == 404
    assert cliente.post("/api/v1/web/dispositivos", json={}).status_code == 404


@pytest.mark.parametrize("estado", [201, 200])
def test_registrar_el_dispositivo_pasa_el_token_y_respeta_el_estado(con_sesion, nucleo, estado):
    nucleo.responder("POST", f"{NUCLEO}/dispositivos", estado, DISPOSITIVO)

    respuesta = con_sesion.post(f"{PREFIJO}/dispositivos", json={"token_push": "fcm:abc"})

    assert respuesta.status_code == estado
    assert respuesta.json() == {
        "id": "DIS-1",
        "plataforma": "android",
        "registrado_en": "2026-11-08T15:00:00Z",
    }
    assert nucleo.cuerpo_de(nucleo.ultima) == {"token": "fcm:abc", "plataforma": "android"}


def test_un_token_vacio_no_llega_al_nucleo(con_sesion, nucleo):
    respuesta = con_sesion.post(f"{PREFIJO}/dispositivos", json={"token_push": ""})

    assert respuesta.status_code == 422
    assert al_nucleo(nucleo) == []


def test_un_token_que_el_nucleo_rechaza_es_422(con_sesion, nucleo):
    nucleo.responder("POST", f"{NUCLEO}/dispositivos", problema(422, "token_push_invalido"))

    respuesta = con_sesion.post(f"{PREFIJO}/dispositivos", json={"token_push": "a b"})

    assert respuesta.status_code == 422
    assert respuesta.json()["codigo"] == "token_push_invalido"


def test_dar_de_baja(con_sesion, nucleo):
    nucleo.responder("DELETE", f"{NUCLEO}/dispositivos/DIS-1", 204)

    respuesta = con_sesion.delete(f"{PREFIJO}/dispositivos/DIS-1")

    assert respuesta.status_code == 204


def test_dar_de_baja_uno_ajeno_es_404(con_sesion, nucleo):
    nucleo.responder(
        "DELETE", f"{NUCLEO}/dispositivos/DIS-9", problema(404, "recurso_no_encontrado")
    )

    respuesta = con_sesion.delete(f"{PREFIJO}/dispositivos/DIS-9")

    assert respuesta.status_code == 404
    assert respuesta.json()["codigo"] == "dispositivo_no_encontrado"


def test_las_notificaciones_traen_los_datos_para_abrir_el_detalle(con_sesion, nucleo):
    nucleo.responder("GET", f"{NUCLEO}/notificaciones", 200, [NOTIFICACION])

    respuesta = con_sesion.get(f"{PREFIJO}/notificaciones", params={"limite": 10})

    [notificacion] = respuesta.json()
    assert notificacion == {k: v for k, v in NOTIFICACION.items() if k != "estado"}
    assert nucleo.ultima.url.params["limite"] == "10"


def test_marcar_leida(con_sesion, nucleo):
    nucleo.responder("POST", f"{NUCLEO}/notificaciones/NOT-1/lectura", 204)

    assert con_sesion.post(f"{PREFIJO}/notificaciones/NOT-1/lectura").status_code == 204


def test_marcar_leida_una_ajena_es_404(con_sesion, nucleo):
    nucleo.responder(
        "POST", f"{NUCLEO}/notificaciones/NOT-9/lectura", problema(404, "recurso_no_encontrado")
    )

    respuesta = con_sesion.post(f"{PREFIJO}/notificaciones/NOT-9/lectura")

    assert respuesta.json()["codigo"] == "notificacion_no_encontrada"
