"""Contrato HTTP del registro y la sesión en `/v1/identidad` (MOB-F01, WEB-F13).

Son las formas que el BFF pasa a la app casi tal cual (`contratos/bff-movil`), así que estas
pruebas cuidan el contrato con el BFF, no la implementación.
"""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.main import crear_app
from app.modulos.auditoria.api import dependencias as auditoria_dependencias
from app.modulos.identidad.api import dependencias

CODIGO = "246810"
RUTA = "/v1/identidad"
REGISTRO = {
    "nombre_completo": "Ana María Rojas",
    "tipo_documento": "cedula_ciudadania",
    "numero_documento": "1020304050",
    "correo": "ana@ejemplo.co",
    "celular": "3001234567",
    "contrasena": "Solventa2026",
    "version_terminos": "2026-09",
}


@pytest.fixture
def nucleo(monkeypatch) -> TestClient:
    monkeypatch.setenv(dependencias.VARIABLE_OTP_FIJO, CODIGO)
    for variable in ("TOKENS_LLAVE_PRIVADA", "TOKENS_LLAVE_PUBLICA", "K_SERVICE"):
        monkeypatch.delenv(variable, raising=False)
    auditoria_dependencias.reiniciar()
    dependencias.reiniciar()
    yield TestClient(crear_app())
    dependencias.reiniciar()
    auditoria_dependencias.reiniciar()


def _registrar(nucleo, **cambios) -> dict:
    respuesta = nucleo.post(f"{RUTA}/registros", json={**REGISTRO, **cambios})
    assert respuesta.status_code == 201, respuesta.text
    return respuesta.json()


def test_registrar_responde_201_con_la_forma_del_contrato_movil(nucleo):
    cuerpo = _registrar(nucleo)

    assert cuerpo["id"].startswith("REG-")
    assert cuerpo["id_cliente"].startswith("CLI-")
    assert cuerpo["estado_identidad"] == "pendiente_verificacion"
    assert set(cuerpo["otp"]) == {"expira_en", "intentos_restantes", "reenvio_disponible_en"}
    assert cuerpo["otp"]["intentos_restantes"] == 3


def test_un_correo_repetido_es_409_correo_ya_registrado(nucleo):
    _registrar(nucleo)

    respuesta = nucleo.post(f"{RUTA}/registros", json={**REGISTRO, "numero_documento": "77777"})

    assert respuesta.status_code == 409
    assert respuesta.json()["title"] == "correo_ya_registrado"


def test_una_contrasena_sin_numero_es_422_contrasena_debil(nucleo):
    respuesta = nucleo.post(f"{RUTA}/registros", json={**REGISTRO, "contrasena": "sinnumeros"})

    assert respuesta.status_code == 422
    assert respuesta.json()["title"] == "contrasena_debil"


@pytest.mark.parametrize(
    "cambio", [{"correo": "no-es-correo"}, {"celular": "123"}, {"contrasena": "corta1"}]
)
def test_los_campos_del_contrato_se_validan(nucleo, cambio):
    assert nucleo.post(f"{RUTA}/registros", json={**REGISTRO, **cambio}).status_code == 422


def test_verificar_el_codigo_abre_la_sesion_del_contrato_movil(nucleo):
    registro = _registrar(nucleo)

    respuesta = nucleo.post(
        f"{RUTA}/registros/{registro['id']}/otp/verificacion", json={"codigo": CODIGO}
    )

    assert respuesta.status_code == 200
    cuerpo = respuesta.json()
    assert set(cuerpo) == {"token_acceso", "token_refresco", "expira_en", "cliente"}
    assert cuerpo["cliente"] == {
        "id": registro["id_cliente"],
        "nombre_completo": "Ana María Rojas",
        "correo": "ana@ejemplo.co",
        "estado_identidad": "pendiente_verificacion",
    }


def test_un_codigo_incorrecto_es_422_con_los_intentos_restantes(nucleo):
    registro = _registrar(nucleo)

    respuesta = nucleo.post(
        f"{RUTA}/registros/{registro['id']}/otp/verificacion", json={"codigo": "000000"}
    )

    assert respuesta.status_code == 422
    assert respuesta.json()["title"] == "otp_invalido"
    assert respuesta.json()["detalles"] == {"intentos_restantes": 2}


def test_reenviar_antes_de_tiempo_es_429_con_retry_after(nucleo):
    registro = _registrar(nucleo)

    respuesta = nucleo.post(f"{RUTA}/registros/{registro['id']}/otp/reenvio")

    assert respuesta.status_code == 429
    assert respuesta.json()["title"] == "otp_reenvio_prematuro"
    assert 0 < int(respuesta.headers["Retry-After"]) <= 30


def test_un_registro_inexistente_es_404_registro_no_encontrado(nucleo):
    respuesta = nucleo.post(f"{RUTA}/registros/REG-2026-999999/otp/reenvio")

    assert respuesta.status_code == 404
    assert respuesta.json()["title"] == "registro_no_encontrado"


def test_la_salud_reporta_tokens_y_envio_de_codigos(nucleo):
    dependencias_identidad = nucleo.get("/salud").json()["modulos"]["identidad"]["dependencias"]

    assert dependencias_identidad["tokens"]["estado"] == "simulado"
    assert dependencias_identidad["envio_codigos"]["estado"] == "simulado"
