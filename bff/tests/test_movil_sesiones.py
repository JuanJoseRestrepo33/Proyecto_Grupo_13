"""Pruebas del registro y la sesión del canal móvil (MOB-F01, WEB-F13)."""

from __future__ import annotations

import pytest

from .conftest import CLIENTE, problema

RUTA = "/api/v1/movil"
NUCLEO = "/v1/identidad"
REGISTRO = {
    "nombre_completo": "Ana María Rojas",
    "tipo_documento": "cedula_ciudadania",
    "numero_documento": "1020304050",
    "correo": "ana@ejemplo.co",
    "celular": "3001234567",
    "contrasena": "Solventa2026",
    "version_terminos": "2026-09",
}
OTP = {
    "expira_en": "2026-10-26T15:10:00Z",
    "intentos_restantes": 3,
    "reenvio_disponible_en": "2026-10-26T15:00:30Z",
}
CUENTA = {
    "id": CLIENTE,
    "nombre_completo": "Ana María Rojas",
    "correo": "ana@ejemplo.co",
    "estado_identidad": "pendiente_verificacion",
}
SESION = {
    "token_acceso": "acceso",
    "token_refresco": "refresco",
    "expira_en": "2026-10-26T15:15:00Z",
    "cliente": CUENTA,
}


def test_registrar_responde_la_forma_del_contrato_sin_datos_internos(cliente, nucleo):
    nucleo.responder(
        "POST",
        f"{NUCLEO}/registros",
        201,
        {
            "id": "REG-2026-000001",
            "id_cliente": CLIENTE,
            "estado_identidad": "pendiente_verificacion",
            "otp": OTP,
        },
    )

    respuesta = cliente.post(f"{RUTA}/registros", json=REGISTRO)

    assert respuesta.status_code == 201
    assert respuesta.json() == {
        "id": "REG-2026-000001",
        "estado_identidad": "pendiente_verificacion",
        "otp": OTP,
    }
    assert nucleo.ultima.headers["X-Canal"] == "movil"
    assert nucleo.cuerpo_de(nucleo.ultima) == REGISTRO


def test_un_correo_ya_registrado_llega_con_su_codigo(cliente, nucleo):
    nucleo.responder("POST", f"{NUCLEO}/registros", problema(409, "correo_ya_registrado"))

    respuesta = cliente.post(f"{RUTA}/registros", json=REGISTRO)

    assert respuesta.status_code == 409
    assert respuesta.json()["codigo"] == "correo_ya_registrado"


def test_un_registro_invalido_trae_un_error_por_campo(cliente):
    respuesta = cliente.post(
        f"{RUTA}/registros", json={**REGISTRO, "celular": "123", "numero_documento": "x"}
    )

    assert respuesta.status_code == 422
    campos = {c["campo"] for c in respuesta.json()["campos"]}
    assert campos == {"celular", "numero_documento"}


def test_verificar_el_codigo_abre_la_sesion(cliente, nucleo):
    nucleo.responder("POST", f"{NUCLEO}/registros/REG-1/otp/verificacion", 200, SESION)

    respuesta = cliente.post(f"{RUTA}/registros/REG-1/otp/verificacion", json={"codigo": "123456"})

    assert respuesta.status_code == 200
    assert respuesta.json() == SESION


def test_un_codigo_incorrecto_trae_los_intentos_restantes(cliente, nucleo):
    nucleo.responder(
        "POST",
        f"{NUCLEO}/registros/REG-1/otp/verificacion",
        problema(422, "otp_invalido", "incorrecto", intentos_restantes=1),
    )

    respuesta = cliente.post(f"{RUTA}/registros/REG-1/otp/verificacion", json={"codigo": "000000"})

    assert respuesta.status_code == 422
    assert respuesta.json() == {
        "codigo": "otp_invalido",
        "mensaje": "incorrecto",
        "intentos_restantes": 1,
    }


@pytest.mark.parametrize("codigo", ["otp_bloqueado", "otp_vencido", "registro_ya_verificado"])
def test_los_409_del_otp_llegan_con_su_codigo(cliente, nucleo, codigo):
    nucleo.responder("POST", f"{NUCLEO}/registros/REG-1/otp/verificacion", problema(409, codigo))

    respuesta = cliente.post(f"{RUTA}/registros/REG-1/otp/verificacion", json={"codigo": "123456"})

    assert (respuesta.status_code, respuesta.json()["codigo"]) == (409, codigo)
    assert "intentos_restantes" not in respuesta.json()


def test_un_codigo_que_no_son_6_digitos_no_llega_al_nucleo(cliente, nucleo):
    respuesta = cliente.post(f"{RUTA}/registros/REG-1/otp/verificacion", json={"codigo": "12a"})

    assert respuesta.status_code == 422
    assert [s for s in nucleo.solicitudes if s.url.path != "/salud"] == []


def test_reenviar_el_codigo(cliente, nucleo):
    nucleo.responder("POST", f"{NUCLEO}/registros/REG-1/otp/reenvio", 202, OTP)

    respuesta = cliente.post(f"{RUTA}/registros/REG-1/otp/reenvio")

    assert respuesta.status_code == 202
    assert respuesta.json() == OTP


def test_reenviar_antes_de_tiempo_trae_retry_after(cliente, nucleo):
    prematuro = problema(429, "otp_reenvio_prematuro")
    prematuro.headers["Retry-After"] = "21"
    nucleo.responder("POST", f"{NUCLEO}/registros/REG-1/otp/reenvio", prematuro)

    respuesta = cliente.post(f"{RUTA}/registros/REG-1/otp/reenvio")

    assert respuesta.status_code == 429
    assert respuesta.headers["Retry-After"] == "21"
    assert respuesta.json()["codigo"] == "otp_reenvio_prematuro"


def test_iniciar_sesion_desde_la_app(cliente, nucleo):
    nucleo.responder("POST", f"{NUCLEO}/sesiones", 200, SESION)

    respuesta = cliente.post(
        f"{RUTA}/sesiones", json={"correo": "ana@ejemplo.co", "contrasena": "Solventa2026"}
    )

    assert respuesta.json() == SESION
    assert nucleo.ultima.headers["X-Canal"] == "movil"


def test_credenciales_invalidas_en_la_app_son_401_con_desafio(cliente, nucleo):
    nucleo.responder("POST", f"{NUCLEO}/sesiones", problema(401, "credenciales_invalidas"))

    respuesta = cliente.post(
        f"{RUTA}/sesiones", json={"correo": "ana@ejemplo.co", "contrasena": "Mala1234"}
    )

    assert respuesta.status_code == 401
    assert respuesta.json()["codigo"] == "credenciales_invalidas"
    assert respuesta.headers["WWW-Authenticate"] == "Bearer"


def test_renovar_la_sesion(cliente, nucleo):
    nucleo.responder("POST", f"{NUCLEO}/sesiones/renovacion", 200, SESION)

    respuesta = cliente.post(f"{RUTA}/sesiones/renovacion", json={"token_refresco": "r1"})

    assert respuesta.json() == SESION
    assert nucleo.cuerpo_de(nucleo.ultima) == {"token_refresco": "r1"}


def test_un_refresco_invalido_es_401_token_refresco_invalido(cliente, nucleo):
    nucleo.responder(
        "POST", f"{NUCLEO}/sesiones/renovacion", problema(401, "token_refresco_invalido")
    )

    respuesta = cliente.post(f"{RUTA}/sesiones/renovacion", json={"token_refresco": "viejo"})

    assert respuesta.status_code == 401
    assert respuesta.json()["codigo"] == "token_refresco_invalido"


def test_un_error_que_no_es_401_al_renovar_no_lleva_desafio(cliente, nucleo):
    nucleo.responder("POST", f"{NUCLEO}/sesiones/renovacion", problema(503, "dependencia_caida"))

    respuesta = cliente.post(f"{RUTA}/sesiones/renovacion", json={"token_refresco": "r1"})

    assert respuesta.status_code == 503
    assert "WWW-Authenticate" not in respuesta.headers


def test_la_sesion_de_la_app_devuelve_el_cliente(cliente, nucleo, firmante):
    nucleo.responder("GET", f"{NUCLEO}/clientes/{CLIENTE}", 200, CUENTA)
    token = firmante.emitir(canal="movil")

    respuesta = cliente.get(f"{RUTA}/sesion", headers={"Authorization": f"Bearer {token}"})

    assert respuesta.json() == CUENTA


def test_sin_token_la_sesion_de_la_app_es_401(cliente):
    respuesta = cliente.get(f"{RUTA}/sesion")

    assert respuesta.status_code == 401
    assert respuesta.headers["WWW-Authenticate"] == "Bearer"


def test_si_la_cuenta_ya_no_existe_la_app_vuelve_a_iniciar_sesion(cliente, nucleo, firmante):
    nucleo.responder("GET", f"{NUCLEO}/clientes/{CLIENTE}", problema(404, "cliente_no_encontrado"))
    token = firmante.emitir(canal="movil")

    respuesta = cliente.get(f"{RUTA}/sesion", headers={"Authorization": f"Bearer {token}"})

    assert respuesta.status_code == 401
    assert respuesta.json()["codigo"] == "sesion_no_vigente"
    assert respuesta.headers["WWW-Authenticate"] == "Bearer"


def test_un_error_del_nucleo_en_la_sesion_de_la_app_no_se_disfraza(cliente, nucleo, firmante):
    nucleo.responder("GET", f"{NUCLEO}/clientes/{CLIENTE}", problema(503, "dependencia_caida"))
    token = firmante.emitir(canal="movil")

    respuesta = cliente.get(f"{RUTA}/sesion", headers={"Authorization": f"Bearer {token}"})

    assert respuesta.status_code == 503
