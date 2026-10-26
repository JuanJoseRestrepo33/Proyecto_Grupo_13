"""Pruebas del cliente del núcleo y de la traducción de sus errores al formato de los canales."""

from __future__ import annotations

import httpx
import pytest
from fastapi import FastAPI
from fastapi.exceptions import RequestValidationError
from fastapi.testclient import TestClient
from pydantic import BaseModel, Field

from app.comun.errores import ErrorBff, manejar_error_bff, manejar_validacion
from app.comun.nucleo import ClienteNucleo

from .conftest import NucleoFalso, problema


@pytest.fixture
def cliente_nucleo(nucleo) -> ClienteNucleo:
    return ClienteNucleo("http://nucleo.prueba", transporte=nucleo.transporte)


def _error(llamada) -> ErrorBff:
    with pytest.raises(ErrorBff) as error:
        llamada()
    return error.value


def test_una_respuesta_exitosa_llega_tal_cual(nucleo, cliente_nucleo):
    nucleo.responder("GET", "/v1/cosa", 200, {"id": "X-1"})

    assert cliente_nucleo.get("/v1/cosa") == {"id": "X-1"}


def test_el_canal_viaja_en_la_cabecera_x_canal(nucleo, cliente_nucleo):
    nucleo.responder("POST", "/v1/cosa", 201, {"id": "X-1"})

    cliente_nucleo.post("/v1/cosa", json={"a": 1}, canal="movil")

    assert nucleo.ultima.headers["X-Canal"] == "movil"
    assert nucleo.cuerpo_de(nucleo.ultima) == {"a": 1}


def test_sin_canal_no_se_envia_la_cabecera(nucleo, cliente_nucleo):
    nucleo.responder("GET", "/v1/cosa", 200, {})

    cliente_nucleo.get("/v1/cosa")

    assert "X-Canal" not in nucleo.ultima.headers


def test_una_respuesta_sin_cuerpo_es_none(nucleo, cliente_nucleo):
    nucleo.responder("POST", "/v1/cosa", 204)

    assert cliente_nucleo.post("/v1/cosa") is None


@pytest.mark.parametrize("estado", [401, 404, 409, 429, 503])
def test_el_problema_del_nucleo_se_traduce_a_codigo_y_mensaje(nucleo, cliente_nucleo, estado):
    nucleo.responder("GET", "/v1/cosa", problema(estado, "regla_x", "no aplica", id="X-1"))

    error = _error(lambda: cliente_nucleo.get("/v1/cosa"))

    assert (error.estado, error.codigo, error.mensaje) == (estado, "regla_x", "no aplica")
    assert error.detalles == {"id": "X-1"}


def test_un_404_toma_el_codigo_del_contrato_del_canal(nucleo, cliente_nucleo):
    nucleo.responder("GET", "/v1/cosa", problema(404, "recurso_no_encontrado"))

    error = _error(lambda: cliente_nucleo.get("/v1/cosa", si_no_existe="cosa_no_encontrada"))

    assert error.codigo == "cosa_no_encontrada"


def test_otros_errores_conservan_su_codigo_aunque_haya_codigo_para_el_404(nucleo, cliente_nucleo):
    nucleo.responder("GET", "/v1/cosa", problema(409, "ya_existe"))

    error = _error(lambda: cliente_nucleo.get("/v1/cosa", si_no_existe="cosa_no_encontrada"))

    assert error.codigo == "ya_existe"


def test_la_validacion_del_nucleo_no_filtra_su_detalle(nucleo, cliente_nucleo):
    nucleo.responder("POST", "/v1/cosa", 422, {"detail": [{"loc": ["body", "x"]}]})

    error = _error(lambda: cliente_nucleo.post("/v1/cosa", json={}))

    assert (error.estado, error.codigo) == (422, "solicitud_invalida")


def test_un_500_del_nucleo_es_un_502_para_el_canal(nucleo, cliente_nucleo):
    nucleo.responder("GET", "/v1/cosa", httpx.Response(500, text="Internal Server Error"))

    error = _error(lambda: cliente_nucleo.get("/v1/cosa"))

    assert (error.estado, error.codigo) == (502, "nucleo_con_error")


def test_un_error_con_cuerpo_que_no_es_objeto_se_traduce_igual(nucleo, cliente_nucleo):
    nucleo.responder("GET", "/v1/cosa", httpx.Response(409, json=["raro"]))

    assert _error(lambda: cliente_nucleo.get("/v1/cosa")).codigo == "error_del_nucleo"


def test_el_retry_after_del_nucleo_llega_al_canal(nucleo, cliente_nucleo):
    respuesta = problema(429, "otp_reenvio_prematuro")
    respuesta.headers["Retry-After"] = "17"
    nucleo.responder("POST", "/v1/cosa", respuesta)

    error = _error(lambda: cliente_nucleo.post("/v1/cosa"))

    assert error.encabezados == {"Retry-After": "17"}


def test_sin_red_hacia_el_nucleo_responde_503_propio():
    def caido(solicitud: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("sin ruta", request=solicitud)

    cliente_nucleo = ClienteNucleo("http://nucleo.prueba", transporte=httpx.MockTransport(caido))

    error = _error(lambda: cliente_nucleo.get("/v1/cosa"))

    assert (error.estado, error.codigo) == (503, "nucleo_no_disponible")
    assert cliente_nucleo.estado() == "inalcanzable"


def test_el_estado_es_el_que_reporta_la_salud_del_nucleo(nucleo, cliente_nucleo):
    assert cliente_nucleo.estado() == "ok"

    nucleo.responder("GET", "/salud", 503, {"estado": "caido"})
    assert cliente_nucleo.estado() == "caido"

    nucleo.responder("GET", "/salud", httpx.Response(200, text="no es json"))
    assert cliente_nucleo.estado() == "inalcanzable"


def test_salud_del_bff_se_degrada_si_el_nucleo_no_responde(nucleo, cliente):
    nucleo.responder("GET", "/salud", 503, {"estado": "caido"})

    respuesta = cliente.get("/salud")

    assert respuesta.status_code == 200
    assert respuesta.json()["estado"] == "degradado"
    assert respuesta.json()["nucleo"]["estado"] == "caido"


# -- formato de error hacia el canal -------------------------------------------


class _Solicitud(BaseModel):
    producto: str = Field(pattern="^(auto|hogar)$")
    edad: int = Field(ge=18, le=100)


@pytest.fixture
def app_de_prueba() -> TestClient:
    app = FastAPI()
    app.add_exception_handler(ErrorBff, manejar_error_bff)
    app.add_exception_handler(RequestValidationError, manejar_validacion)

    @app.post("/eco")
    def eco(solicitud: _Solicitud) -> dict[str, object]:
        return solicitud.model_dump()

    @app.get("/falla")
    def falla() -> None:
        raise ErrorBff(
            409,
            "otp_invalido",
            "código incorrecto",
            encabezados={"Retry-After": "3"},
            extra={"intentos_restantes": 2},
        )

    return TestClient(app)


def test_el_error_llega_con_codigo_mensaje_y_sus_extras(app_de_prueba):
    respuesta = app_de_prueba.get("/falla")

    assert respuesta.status_code == 409
    assert respuesta.json() == {
        "codigo": "otp_invalido",
        "mensaje": "código incorrecto",
        "intentos_restantes": 2,
    }
    assert respuesta.headers["Retry-After"] == "3"


def test_la_validacion_trae_un_error_por_campo(app_de_prueba):
    respuesta = app_de_prueba.post("/eco", json={"producto": "moto", "edad": 12})

    assert respuesta.status_code == 422
    cuerpo = respuesta.json()
    assert cuerpo["codigo"] == "producto_invalido"
    assert cuerpo["campos"] == [
        {"campo": "producto", "codigo": "formato_invalido"},
        {"campo": "edad", "codigo": "fuera_de_rango"},
    ]


def test_un_campo_sin_codigo_propio_da_solicitud_invalida(app_de_prueba):
    cuerpo = app_de_prueba.post("/eco", json={"producto": "auto"}).json()

    assert cuerpo["codigo"] == "solicitud_invalida"
    assert cuerpo["campos"] == [{"campo": "edad", "codigo": "obligatorio"}]


def test_un_cuerpo_ilegible_se_reporta_sobre_el_cuerpo(app_de_prueba):
    respuesta = app_de_prueba.post(
        "/eco", content=b"{no es json", headers={"content-type": "application/json"}
    )

    assert respuesta.status_code == 422
    assert respuesta.json()["codigo"] == "solicitud_invalida"


def test_el_nucleo_falso_responde_404_a_lo_no_programado():
    falso = NucleoFalso()

    assert falso(httpx.Request("GET", "http://nucleo.prueba/v1/nada")).status_code == 404
