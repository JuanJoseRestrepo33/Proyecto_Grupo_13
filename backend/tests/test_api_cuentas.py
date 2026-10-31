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


# -- sesión (WEB-F13) -------------------------------------------------------------------


def _verificada(nucleo, **cambios) -> dict:
    registro = _registrar(nucleo, **cambios)
    nucleo.post(f"{RUTA}/registros/{registro['id']}/otp/verificacion", json={"codigo": CODIGO})
    return registro


def test_iniciar_sesion_desde_la_web_con_la_cuenta_de_la_app(nucleo):
    registro = _verificada(nucleo)

    respuesta = nucleo.post(
        f"{RUTA}/sesiones",
        json={"correo": "ana@ejemplo.co", "contrasena": "Solventa2026"},
        headers={"X-Canal": "web"},
    )

    assert respuesta.status_code == 200
    assert respuesta.json()["cliente"]["id"] == registro["id_cliente"]


@pytest.mark.parametrize(
    "credenciales",
    [
        {"correo": "ana@ejemplo.co", "contrasena": "Equivocada1"},
        {"correo": "nadie@ejemplo.co", "contrasena": "Solventa2026"},
    ],
)
def test_credenciales_invalidas_son_401_iguales(nucleo, credenciales):
    _verificada(nucleo)

    respuesta = nucleo.post(f"{RUTA}/sesiones", json=credenciales)

    assert respuesta.status_code == 401
    assert respuesta.json()["title"] == "credenciales_invalidas"
    assert respuesta.json()["detail"] == "Correo o contraseña incorrectos"


def test_renovar_por_http_rota_el_refresco(nucleo):
    _verificada(nucleo)
    sesion = nucleo.post(
        f"{RUTA}/sesiones", json={"correo": "ana@ejemplo.co", "contrasena": "Solventa2026"}
    ).json()

    renovada = nucleo.post(
        f"{RUTA}/sesiones/renovacion", json={"token_refresco": sesion["token_refresco"]}
    )
    repetida = nucleo.post(
        f"{RUTA}/sesiones/renovacion", json={"token_refresco": sesion["token_refresco"]}
    )

    assert renovada.status_code == 200
    assert repetida.status_code == 401
    assert repetida.json()["title"] == "token_refresco_invalido"


def test_el_cliente_y_su_sesion_por_http(nucleo):
    registro = _verificada(nucleo)
    token = nucleo.post(
        f"{RUTA}/sesiones", json={"correo": "ana@ejemplo.co", "contrasena": "Solventa2026"}
    ).json()["token_acceso"]

    por_id = nucleo.get(f"{RUTA}/clientes/{registro['id_cliente']}")
    por_token = nucleo.get(f"{RUTA}/sesion", headers={"Authorization": f"Bearer {token}"})

    assert por_id.status_code == 200
    assert por_token.json() == por_id.json()
    assert por_id.json()["correo"] == "ana@ejemplo.co"


def test_sin_token_o_con_uno_invalido_la_sesion_es_401(nucleo):
    assert nucleo.get(f"{RUTA}/sesion").json()["title"] == "sesion_no_vigente"
    respuesta = nucleo.get(f"{RUTA}/sesion", headers={"Authorization": "Bearer basura"})
    assert respuesta.status_code == 401


def test_un_cliente_inexistente_es_404_por_http(nucleo):
    respuesta = nucleo.get(f"{RUTA}/clientes/CLI-2026-999999")

    assert respuesta.status_code == 404
    assert respuesta.json()["title"] == "cliente_no_encontrado"


def test_verificar_sin_cuerpo_usa_los_datos_de_la_cuenta(nucleo):
    id_cliente = _verificada(nucleo, numero_documento="1020304057")["id_cliente"]

    respuesta = nucleo.post(f"{RUTA}/clientes/{id_cliente}/verificaciones")

    assert respuesta.status_code == 202
    assert respuesta.json()["estado"] == "rechazada"
    assert respuesta.json()["motivo"] == "datos_no_coinciden"
    cuenta = nucleo.get(f"{RUTA}/clientes/{id_cliente}").json()
    assert cuenta["estado_identidad"] == "rechazada"


def test_el_cliente_queda_verificado_tras_la_verificacion_sin_cuerpo(nucleo):
    id_cliente = _verificada(nucleo)["id_cliente"]
    ruta = f"{RUTA}/clientes/{id_cliente}"
    assert nucleo.get(f"{ruta}/verificaciones/ultima").status_code == 404

    assert nucleo.post(f"{ruta}/verificaciones").json()["estado"] == "aprobada"

    assert nucleo.get(ruta).json()["estado_identidad"] == "verificada"
    assert nucleo.post(f"{ruta}/verificaciones").status_code == 409


def test_verificar_sin_cuerpo_a_un_cliente_inexistente_es_404(nucleo):
    respuesta = nucleo.post(f"{RUTA}/clientes/CLI-2026-999999/verificaciones")

    assert respuesta.status_code == 404
    assert respuesta.json()["title"] == "cliente_no_encontrado"


def test_quien_no_confirmo_el_correo_no_puede_verificar_su_identidad(nucleo):
    id_cliente = _registrar(nucleo)["id_cliente"]
    ruta = f"{RUTA}/clientes/{id_cliente}/verificaciones"
    sesion = nucleo.post(
        f"{RUTA}/sesiones", json={"correo": "ana@ejemplo.co", "contrasena": "Solventa2026"}
    )
    assert sesion.status_code == 200

    respuesta = nucleo.post(ruta)

    assert respuesta.status_code == 409
    assert respuesta.json()["title"] == "correo_no_verificado"
    assert nucleo.get(f"{ruta}/ultima").status_code == 404


def test_un_rechazo_por_datos_no_vuelve_al_proveedor_con_los_mismos(nucleo):
    id_cliente = _verificada(nucleo, numero_documento="1020304057")["id_cliente"]
    ruta = f"{RUTA}/clientes/{id_cliente}/verificaciones"
    rechazo = nucleo.post(ruta).json()

    respuesta = nucleo.post(ruta)

    assert respuesta.status_code == 409
    assert respuesta.json()["title"] == "reintento_sin_cambios"
    assert nucleo.get(f"{ruta}/ultima").json()["id"] == rechazo["id"]


def test_con_humo_correo_y_clave_la_cuenta_de_prueba_existe_al_arrancar(monkeypatch):
    monkeypatch.setenv(dependencias.VARIABLE_CORREO_PRUEBA, "humo@solventa.co")
    monkeypatch.setenv(dependencias.VARIABLE_CLAVE_PRUEBA, "Humo2026x")
    auditoria_dependencias.reiniciar()
    dependencias.reiniciar()
    try:
        respuesta = TestClient(crear_app()).post(
            f"{RUTA}/sesiones",
            json={"correo": "humo@solventa.co", "contrasena": "Humo2026x"},
            headers={"X-Canal": "movil"},
        )
    finally:
        dependencias.reiniciar()
        auditoria_dependencias.reiniciar()

    assert respuesta.status_code == 200
    assert respuesta.json()["cliente"]["nombre_completo"] == "Cuenta de prueba"
