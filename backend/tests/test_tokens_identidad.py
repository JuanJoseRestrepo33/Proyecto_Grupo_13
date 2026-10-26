"""Pruebas de la emisión y validación de tokens ES256 en el núcleo (WEB-F13)."""

from __future__ import annotations

import base64
import hashlib
import json
from datetime import UTC, datetime, timedelta

import jwt
import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec
from jwt.algorithms import ECAlgorithm

from app.modulos.identidad.api.dependencias import crear_emisor
from app.modulos.identidad.dominio.sesion import SesionNoVigenteError
from app.modulos.identidad.infraestructura.tokens_es256 import (
    EMISOR,
    EmisorEs256,
    LlaveInvalidaError,
    cargar_publica,
    huella_jwk,
)

AHORA = datetime(2026, 10, 26, 15, 0, tzinfo=UTC)
QUINCE_MINUTOS = timedelta(minutes=15)


def _pem_privada(llave: ec.EllipticCurvePrivateKey) -> str:
    return llave.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    ).decode()


@pytest.fixture
def privada() -> ec.EllipticCurvePrivateKey:
    return ec.generate_private_key(ec.SECP256R1())


@pytest.fixture
def emisor(privada) -> EmisorEs256:
    return EmisorEs256(privada)


def test_el_kid_es_la_huella_jwk_de_la_publica(emisor, privada):
    jwk = ECAlgorithm.to_jwk(privada.public_key(), as_dict=True)
    canonico = json.dumps({k: jwk[k] for k in ("crv", "kty", "x", "y")}, separators=(",", ":"))
    esperado = base64.urlsafe_b64encode(hashlib.sha256(canonico.encode()).digest())

    assert emisor.kid == esperado.rstrip(b"=").decode()
    assert huella_jwk(cargar_publica(emisor.pem_publica())) == emisor.kid


def test_el_token_trae_los_reclamos_acordados_con_el_bff(emisor):
    emitido = emisor.emitir_acceso("CLI-1", "movil", AHORA, QUINCE_MINUTOS)

    encabezado = jwt.get_unverified_header(emitido.token)
    datos = jwt.decode(emitido.token, options={"verify_signature": False})
    assert encabezado == {"alg": "ES256", "kid": emisor.kid, "typ": "JWT"}
    assert datos == {
        "sub": "CLI-1",
        "iss": EMISOR,
        "iat": int(AHORA.timestamp()),
        "exp": int((AHORA + QUINCE_MINUTOS).timestamp()),
        "tipo": "acceso",
        "canal": "movil",
    }
    assert emitido.expira_en == AHORA + QUINCE_MINUTOS


def test_el_bff_valida_el_token_solo_con_la_llave_publica(emisor):
    """Lo que hace el BFF: PyJWT con la pública, sin nada del núcleo."""
    emitido = emisor.emitir_acceso("CLI-1", "web", datetime.now(UTC), QUINCE_MINUTOS)

    datos = jwt.decode(
        emitido.token, cargar_publica(emisor.pem_publica()), algorithms=["ES256"], issuer=EMISOR
    )

    assert datos["sub"] == "CLI-1"


def test_el_nucleo_valida_lo_que_emite(emisor):
    emitido = emisor.emitir_acceso("CLI-1", "web", AHORA, QUINCE_MINUTOS)

    reclamos = emisor.validar_acceso(emitido.token, AHORA + timedelta(minutes=14))

    assert (reclamos.id_cliente, reclamos.canal) == ("CLI-1", "web")


def test_un_token_vencido_no_es_valido(emisor):
    emitido = emisor.emitir_acceso("CLI-1", "web", AHORA, QUINCE_MINUTOS)

    with pytest.raises(SesionNoVigenteError):
        emisor.validar_acceso(emitido.token, AHORA + timedelta(minutes=16))


def test_un_token_de_otra_llave_no_es_valido(emisor):
    otro = EmisorEs256.efimero().emitir_acceso("CLI-1", "web", AHORA, QUINCE_MINUTOS)

    with pytest.raises(SesionNoVigenteError):
        emisor.validar_acceso(otro.token, AHORA)


def test_un_token_alterado_no_es_valido(emisor):
    token = emisor.emitir_acceso("CLI-1", "web", AHORA, QUINCE_MINUTOS).token
    encabezado, carga, firma = token.split(".")
    otra_carga = base64.urlsafe_b64encode(
        json.dumps({"sub": "CLI-2", "iss": EMISOR, "exp": 9999999999, "iat": 0}).encode()
    )

    with pytest.raises(SesionNoVigenteError):
        emisor.validar_acceso(f"{encabezado}.{otra_carga.rstrip(b'=').decode()}.{firma}", AHORA)


@pytest.mark.parametrize("token", ["", "basura", "a.b.c"])
def test_un_token_mal_formado_no_es_valido(emisor, token):
    with pytest.raises(SesionNoVigenteError):
        emisor.validar_acceso(token, AHORA)


def test_un_token_firmado_por_el_emisor_pero_sin_tipo_acceso_no_abre_sesion(emisor, privada):
    token = jwt.encode(
        {"sub": "CLI-1", "iss": EMISOR, "iat": 0, "exp": 9999999999, "tipo": "refresco"},
        privada,
        algorithm="ES256",
        headers={"kid": emisor.kid},
    )

    with pytest.raises(SesionNoVigenteError):
        emisor.validar_acceso(token, AHORA)


def test_una_llave_de_otra_curva_se_rechaza():
    with pytest.raises(LlaveInvalidaError):
        EmisorEs256(ec.generate_private_key(ec.SECP384R1()))


# -- composición según el entorno ------------------------------------------------


def test_con_la_llave_privada_el_estado_es_ok(privada):
    emisor, estado = crear_emisor({"TOKENS_LLAVE_PRIVADA": _pem_privada(privada)})

    assert estado.estado == "ok"
    assert emisor.kid in estado.detalle
    assert emisor.efimera is False


def test_la_publica_configurada_tiene_que_ser_la_de_la_privada(privada):
    emisor = EmisorEs256(privada)
    pem_privada = _pem_privada(privada)

    _, iguales = crear_emisor(
        {"TOKENS_LLAVE_PRIVADA": pem_privada, "TOKENS_LLAVE_PUBLICA": emisor.pem_publica()}
    )
    _, distintas = crear_emisor(
        {
            "TOKENS_LLAVE_PRIVADA": pem_privada,
            "TOKENS_LLAVE_PUBLICA": EmisorEs256.efimero().pem_publica(),
        }
    )

    assert iguales.estado == "ok"
    assert distintas.estado == "caido"


def test_sin_llave_en_local_se_usa_una_efimera():
    emisor, estado = crear_emisor({})

    assert estado.estado == "simulado"
    assert emisor.efimera is True


def test_sin_llave_en_cloud_run_los_tokens_quedan_caidos():
    _, estado = crear_emisor({"K_SERVICE": "solventa-nucleo"})

    assert estado.estado == "caido"
    assert "TOKENS_LLAVE_PRIVADA" in estado.detalle


def test_una_llave_ilegible_deja_los_tokens_caidos():
    _, estado = crear_emisor({"TOKENS_LLAVE_PRIVADA": "no es una pem"})

    assert estado.estado == "caido"
