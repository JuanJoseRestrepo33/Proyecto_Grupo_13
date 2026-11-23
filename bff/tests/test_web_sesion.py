"""Pruebas de la sesión del canal web: iniciar, consultar y cerrar (WEB-F13)."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

import pytest

from app.web.dependencias import COOKIE_SESION

from .conftest import CLIENTE, al_nucleo, problema

RUTA = "/api/v1/web/sesion"
NUCLEO = "/v1/identidad"
CREDENCIALES = {"correo": "ana@ejemplo.co", "contrasena": "Solventa2026"}
CUENTA = {
    "id": CLIENTE,
    "nombre_completo": "Ana María Rojas",
    "correo": "ana@ejemplo.co",
    "estado_identidad": "pendiente_verificacion",
}


def sesion_del_nucleo(token: str = "token-de-acceso", minutos: int = 60) -> dict:
    return {
        "token_acceso": token,
        "token_refresco": "refresco-que-la-web-no-usa",
        "expira_en": (datetime.now(UTC) + timedelta(minutes=minutos)).isoformat(),
        "cliente": CUENTA,
    }


def test_iniciar_sesion_pone_la_cookie_y_responde_el_cliente(cliente, nucleo):
    nucleo.responder("POST", f"{NUCLEO}/sesiones", 200, sesion_del_nucleo())

    respuesta = cliente.post(RUTA, json=CREDENCIALES)

    assert respuesta.status_code == 200
    assert respuesta.json() == {"cliente": {"id": CLIENTE, "nombre": "Ana"}}
    cookie = respuesta.headers["set-cookie"]
    assert cookie.startswith(f"{COOKIE_SESION}=token-de-acceso;")
    for atributo in ("HttpOnly", "Secure", "SameSite=lax", "Path=/api/v1/web"):
        assert atributo in cookie
    assert "Max-Age=35" in cookie or "Max-Age=36" in cookie  # 3599 o 3600 segundos


def test_la_web_nunca_recibe_el_token_en_el_cuerpo(cliente, nucleo):
    nucleo.responder("POST", f"{NUCLEO}/sesiones", 200, sesion_del_nucleo())

    cuerpo = cliente.post(RUTA, json=CREDENCIALES).text

    assert "token-de-acceso" not in cuerpo
    assert "refresco" not in cuerpo


def test_iniciar_sesion_pide_al_nucleo_con_el_canal_web(cliente, nucleo):
    nucleo.responder("POST", f"{NUCLEO}/sesiones", 200, sesion_del_nucleo())

    cliente.post(RUTA, json=CREDENCIALES)

    assert nucleo.ultima.headers["X-Canal"] == "web"
    assert nucleo.cuerpo_de(nucleo.ultima) == CREDENCIALES


def test_credenciales_invalidas_son_401_y_no_ponen_cookie(cliente, nucleo):
    nucleo.responder(
        "POST",
        f"{NUCLEO}/sesiones",
        problema(401, "credenciales_invalidas", "Correo o contraseña incorrectos"),
    )

    respuesta = cliente.post(RUTA, json=CREDENCIALES)

    assert respuesta.status_code == 401
    assert respuesta.json()["codigo"] == "credenciales_invalidas"
    assert "set-cookie" not in respuesta.headers


@pytest.mark.parametrize(
    "cuerpo", [{"correo": "no-es-correo", "contrasena": "x"}, {"correo": "a@b.co"}]
)
def test_credenciales_mal_formadas_son_422(cliente, nucleo, cuerpo):
    respuesta = cliente.post(RUTA, json=cuerpo)

    assert respuesta.status_code == 422
    assert al_nucleo(nucleo) == []


def test_consultar_la_sesion_con_la_cookie(cliente, nucleo, firmante):
    nucleo.responder("GET", f"{NUCLEO}/clientes/{CLIENTE}", 200, CUENTA)
    cliente.cookies.set(COOKIE_SESION, firmante.emitir(canal="web"))

    respuesta = cliente.get(RUTA)

    assert respuesta.status_code == 200
    assert respuesta.json() == {"cliente": {"id": CLIENTE, "nombre": "Ana"}}


def test_sin_cookie_o_con_cookie_vencida_la_sesion_es_401(cliente, firmante):
    """Criterio 4: con la sesión vencida, la web pide iniciar sesión de nuevo."""
    assert cliente.get(RUTA).json()["codigo"] == "sesion_no_vigente"

    cliente.cookies.set(COOKIE_SESION, firmante.emitir(canal="web", vigencia=-60))
    respuesta = cliente.get(RUTA)

    assert respuesta.status_code == 401
    assert respuesta.json()["codigo"] == "sesion_no_vigente"


def test_si_la_cuenta_ya_no_existe_en_el_nucleo_la_sesion_es_401(cliente, nucleo, firmante):
    nucleo.responder("GET", f"{NUCLEO}/clientes/{CLIENTE}", problema(404, "cliente_no_encontrado"))
    cliente.cookies.set(COOKIE_SESION, firmante.emitir(canal="web"))

    respuesta = cliente.get(RUTA)

    assert respuesta.status_code == 401
    assert respuesta.json()["codigo"] == "sesion_no_vigente"


def test_otro_404_del_nucleo_no_vence_la_sesion(cliente, nucleo, firmante):
    nucleo.responder("GET", f"{NUCLEO}/clientes/{CLIENTE}", problema(404, "recurso_no_encontrado"))
    cliente.cookies.set(COOKIE_SESION, firmante.emitir(canal="web"))

    respuesta = cliente.get(RUTA)

    assert respuesta.status_code == 404
    assert respuesta.json()["codigo"] == "recurso_no_encontrado"


def test_un_error_del_nucleo_al_consultar_la_sesion_no_se_disfraza(cliente, nucleo, firmante):
    nucleo.responder("GET", f"{NUCLEO}/clientes/{CLIENTE}", problema(503, "dependencia_caida"))
    cliente.cookies.set(COOKIE_SESION, firmante.emitir(canal="web"))

    assert cliente.get(RUTA).status_code == 503


@pytest.mark.parametrize("cookie", [None, "basura", "vencida"])
def test_cerrar_sesion_siempre_responde_204_y_borra_la_cookie(cliente, firmante, cookie):
    """Criterio de Harold: la web siempre puede cerrar la sesión."""
    if cookie == "vencida":
        cliente.cookies.set(COOKIE_SESION, firmante.emitir(canal="web", vigencia=-60))
    elif cookie:
        cliente.cookies.set(COOKIE_SESION, cookie)

    respuesta = cliente.delete(RUTA)

    assert respuesta.status_code == 204
    borrado = respuesta.headers["set-cookie"]
    assert borrado.startswith(f'{COOKIE_SESION}="";') or borrado.startswith(f"{COOKIE_SESION}=;")
    assert "Max-Age=0" in borrado
    assert "Path=/api/v1/web" in borrado
