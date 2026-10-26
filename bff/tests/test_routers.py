"""Pruebas de los dos routers: prefijo de cada canal y forma de llevar la sesión."""

from __future__ import annotations

import pytest
from fastapi import APIRouter
from fastapi.testclient import TestClient

from app.main import crear_app
from app.movil.dependencias import ClienteMovil
from app.web.dependencias import COOKIE_SESION, ClienteWeb

from .conftest import CLIENTE


@pytest.fixture
def canales(configuracion, nucleo) -> TestClient:
    """El BFF con una ruta protegida de prueba en cada canal."""
    app = crear_app(configuracion, nucleo.transporte)
    web, movil = APIRouter(prefix="/api/v1/web"), APIRouter(prefix="/api/v1/movil")

    @web.get("/quien")
    def quien_web(cliente: ClienteWeb) -> dict[str, str]:
        return {"cliente": cliente.id_cliente}

    @movil.get("/quien")
    def quien_movil(cliente: ClienteMovil) -> dict[str, str]:
        return {"cliente": cliente.id_cliente}

    app.include_router(web)
    app.include_router(movil)
    return TestClient(app)


def test_la_web_lleva_la_sesion_en_la_cookie(canales, firmante):
    canales.cookies.set(COOKIE_SESION, firmante.emitir(canal="web"))

    assert canales.get("/api/v1/web/quien").json() == {"cliente": CLIENTE}


def test_la_app_lleva_la_sesion_en_authorization_bearer(canales, firmante):
    token = firmante.emitir(canal="movil")

    respuesta = canales.get("/api/v1/movil/quien", headers={"Authorization": f"Bearer {token}"})

    assert respuesta.json() == {"cliente": CLIENTE}


def test_la_web_no_acepta_el_token_en_authorization(canales, firmante):
    token = firmante.emitir(canal="web")

    respuesta = canales.get("/api/v1/web/quien", headers={"Authorization": f"Bearer {token}"})

    assert respuesta.status_code == 401
    assert respuesta.json()["codigo"] == "sesion_no_vigente"


@pytest.mark.parametrize("cabecera", [None, "Basic dXN1YXJpbw==", "Bearer ", "Bearer basura"])
def test_la_app_sin_bearer_valido_recibe_401_con_desafio(canales, cabecera):
    encabezados = {"Authorization": cabecera} if cabecera else {}

    respuesta = canales.get("/api/v1/movil/quien", headers=encabezados)

    assert respuesta.status_code == 401
    assert respuesta.json()["codigo"] == "sesion_no_vigente"
    assert respuesta.headers["WWW-Authenticate"] == "Bearer"


def test_una_cookie_de_la_web_no_abre_sesion_en_la_app(canales, firmante):
    token = firmante.emitir(canal="web")

    respuesta = canales.get("/api/v1/movil/quien", headers={"Authorization": f"Bearer {token}"})

    assert respuesta.status_code == 401
