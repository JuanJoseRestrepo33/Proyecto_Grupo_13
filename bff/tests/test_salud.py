"""Pruebas de `/salud` y de la configuración que el BFF lee del entorno."""

from __future__ import annotations

from app.comun.configuracion import NUCLEO_LOCAL, Configuracion


def test_salud_responde_ok(cliente):
    respuesta = cliente.get("/salud")

    assert respuesta.status_code == 200
    assert respuesta.json()["estado"] == "ok"
    assert respuesta.json()["nucleo"] == {"url": "http://nucleo.prueba", "estado": "ok"}


def test_la_url_del_nucleo_sale_del_entorno_sin_barra_final():
    configuracion = Configuracion.desde_entorno({"NUCLEO_URL": "https://nucleo.example/"})

    assert configuracion.nucleo_url == "https://nucleo.example"


def test_sin_variables_apunta_al_nucleo_local():
    configuracion = Configuracion.desde_entorno({})

    assert configuracion.nucleo_url == NUCLEO_LOCAL
    assert configuracion.llaves_publicas == ()
    assert configuracion.en_la_nube is False


def test_lee_la_llave_actual_y_la_anterior_de_una_rotacion():
    configuracion = Configuracion.desde_entorno(
        {
            "TOKENS_LLAVE_PUBLICA": "actual\n",
            "TOKENS_LLAVE_PUBLICA_ANTERIOR": "anterior",
            "K_SERVICE": "solventa-bff",
        }
    )

    assert configuracion.llaves_publicas == ("actual", "anterior")
    assert configuracion.en_la_nube is True
