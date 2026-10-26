"""Pruebas de la validación de tokens ES256 y de la sesión que se deriva de ellos."""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import time

import jwt
import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec, rsa
from fastapi import Request
from fastapi.testclient import TestClient
from jwt.algorithms import ECAlgorithm

from app.comun.configuracion import Configuracion
from app.comun.errores import ErrorBff, manejar_error_bff
from app.comun.sesion import autenticar
from app.comun.tokens import (
    LlaveInvalida,
    TokenInvalido,
    VerificadorDeTokens,
    cargar_llave_publica,
    huella_jwk,
)
from app.main import crear_app

from .conftest import CLIENTE, Firmante


@pytest.fixture
def verificador(firmante) -> VerificadorDeTokens:
    return VerificadorDeTokens.desde_pem([firmante.pem_publica])


def _b64(datos: bytes) -> str:
    return base64.urlsafe_b64encode(datos).rstrip(b"=").decode()


# -- huella JWK (RFC 7638) ------------------------------------------------------


def test_la_huella_es_el_sha256_del_jwk_canonico(firmante):
    jwk = ECAlgorithm.to_jwk(firmante.privada.public_key(), as_dict=True)
    canonico = json.dumps({k: jwk[k] for k in ("crv", "kty", "x", "y")}, separators=(",", ":"))

    assert firmante.kid == _b64(hashlib.sha256(canonico.encode()).digest())


def test_la_huella_no_lleva_relleno_y_mide_43_caracteres(firmante):
    assert len(firmante.kid) == 43
    assert "=" not in firmante.kid


def test_la_huella_es_la_misma_desde_la_pem(firmante):
    assert huella_jwk(cargar_llave_publica(firmante.pem_publica)) == firmante.kid


# -- validación -----------------------------------------------------------------


def test_un_token_valido_dice_cliente_canal_y_vencimiento(firmante, verificador):
    reclamos = verificador.verificar(firmante.emitir(canal="movil", vigencia=600))

    assert reclamos.id_cliente == CLIENTE
    assert reclamos.canal == "movil"
    assert abs(reclamos.expira_en.timestamp() - (time.time() + 600)) < 5


@pytest.mark.parametrize(
    "token",
    [
        "no-es-un-jwt",
        "",
        "a.b.c",
    ],
)
def test_un_token_mal_formado_es_invalido(verificador, token):
    with pytest.raises(TokenInvalido):
        verificador.verificar(token)


def test_un_token_vencido_es_invalido(firmante, verificador):
    with pytest.raises(TokenInvalido):
        verificador.verificar(firmante.emitir(vigencia=-60))


def test_un_token_firmado_con_otra_llave_es_invalido(verificador):
    with pytest.raises(TokenInvalido):
        verificador.verificar(Firmante().emitir())


def test_un_token_con_el_kid_correcto_pero_otra_firma_es_invalido(firmante, verificador):
    impostor = Firmante()
    token = impostor.emitir(encabezado={"kid": firmante.kid})

    with pytest.raises(TokenInvalido):
        verificador.verificar(token)


def test_un_token_hs256_como_el_del_humo_es_invalido(firmante, verificador):
    """El humo de `cd.yml` manda uno así y espera 401, nunca 5xx ni 200."""
    encabezado = _b64(json.dumps({"alg": "HS256", "typ": "JWT", "kid": firmante.kid}).encode())
    carga = _b64(json.dumps({"sub": "humo", "exp": int(time.time()) + 300}).encode())
    firma = _b64(hmac.new(b"secreto", f"{encabezado}.{carga}".encode(), "sha256").digest())

    with pytest.raises(TokenInvalido):
        verificador.verificar(f"{encabezado}.{carga}.{firma}")


def test_un_token_de_refresco_no_abre_sesion(firmante, verificador):
    with pytest.raises(TokenInvalido):
        verificador.verificar(firmante.emitir(tipo="refresco"))


def test_un_token_de_otro_emisor_es_invalido(firmante, verificador):
    with pytest.raises(TokenInvalido):
        verificador.verificar(firmante.emitir(iss="otro-emisor"))


def test_durante_una_rotacion_valen_la_llave_actual_y_la_anterior(firmante):
    anterior = Firmante()
    verificador = VerificadorDeTokens.desde_pem([firmante.pem_publica, anterior.pem_publica])

    assert verificador.verificar(anterior.emitir()).id_cliente == CLIENTE
    assert verificador.verificar(firmante.emitir()).id_cliente == CLIENTE
    assert set(verificador.kids) == {firmante.kid, anterior.kid}


def test_una_pem_que_no_es_llave_se_rechaza():
    with pytest.raises(LlaveInvalida):
        cargar_llave_publica("-----BEGIN PUBLIC KEY-----\nno\n-----END PUBLIC KEY-----")


def test_una_llave_que_no_es_p256_se_rechaza():
    pem = (
        rsa.generate_private_key(public_exponent=65537, key_size=2048)
        .public_key()
        .public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo)
        .decode()
    )
    otra_curva = (
        ec.generate_private_key(ec.SECP384R1())
        .public_key()
        .public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo)
        .decode()
    )

    for llave in (pem, otra_curva):
        with pytest.raises(LlaveInvalida):
            cargar_llave_publica(llave)


# -- sesión ---------------------------------------------------------------------


@pytest.fixture
def protegida(configuracion, nucleo) -> TestClient:
    app = crear_app(configuracion, nucleo.transporte)

    @app.get("/protegida")
    def protegida(request: Request) -> dict[str, str]:
        token = request.headers.get("X-Token")
        return {"cliente": autenticar(request, token, "web").id_cliente}

    return TestClient(app)


def test_con_un_token_valido_del_canal_la_ruta_conoce_al_cliente(protegida, firmante):
    respuesta = protegida.get("/protegida", headers={"X-Token": firmante.emitir()})

    assert respuesta.json() == {"cliente": CLIENTE}


@pytest.mark.parametrize("token", [None, "basura"])
def test_sin_token_valido_responde_401_sesion_no_vigente(protegida, token):
    encabezados = {"X-Token": token} if token else {}

    respuesta = protegida.get("/protegida", headers=encabezados)

    assert respuesta.status_code == 401
    assert respuesta.json()["codigo"] == "sesion_no_vigente"


def test_un_token_de_otro_canal_no_abre_sesion(protegida, firmante):
    respuesta = protegida.get("/protegida", headers={"X-Token": firmante.emitir(canal="movil")})

    assert respuesta.status_code == 401


def test_sin_llave_configurada_un_token_es_503_y_no_401(nucleo, firmante):
    app = crear_app(Configuracion(nucleo_url="http://nucleo.prueba"), nucleo.transporte)
    app.add_exception_handler(ErrorBff, manejar_error_bff)

    @app.get("/protegida")
    def protegida(request: Request) -> None:
        autenticar(request, request.headers.get("X-Token"), "web")

    cliente = TestClient(app)

    assert cliente.get("/protegida").status_code == 401
    respuesta = cliente.get("/protegida", headers={"X-Token": firmante.emitir()})
    assert respuesta.status_code == 503
    assert respuesta.json()["codigo"] == "llave_no_configurada"


# -- /salud según las llaves ------------------------------------------------------


def test_salud_reporta_los_kids_configurados(cliente, firmante):
    tokens = cliente.get("/salud").json()["tokens"]

    assert tokens == {"estado": "ok", "kids": [firmante.kid]}


def test_sin_llave_en_local_la_salud_queda_degradada(nucleo):
    cliente = TestClient(crear_app(Configuracion(nucleo_url="http://n.prueba"), nucleo.transporte))

    respuesta = cliente.get("/salud")

    assert respuesta.status_code == 200
    assert respuesta.json()["estado"] == "degradado"


def test_sin_llave_en_cloud_run_la_salud_responde_503(nucleo):
    configuracion = Configuracion(nucleo_url="http://n.prueba", en_la_nube=True)
    cliente = TestClient(crear_app(configuracion, nucleo.transporte))

    respuesta = cliente.get("/salud")

    assert respuesta.status_code == 503
    assert respuesta.json()["tokens"]["estado"] == "caido"


def test_una_llave_invalida_responde_503_aunque_sea_local(nucleo):
    configuracion = Configuracion(nucleo_url="http://n.prueba", llaves_publicas=("no es pem",))
    cliente = TestClient(crear_app(configuracion, nucleo.transporte))

    respuesta = cliente.get("/salud")

    assert respuesta.status_code == 503
    assert "PEM" in respuesta.json()["tokens"]["detalle"]


def test_jwt_y_cryptography_estan_disponibles():
    """La imagen instala `pyjwt[crypto]`: sin cryptography, ES256 no existe."""
    assert "ES256" in jwt.algorithms.get_default_algorithms()
