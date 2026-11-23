"""Validación de los tokens de sesión que emite el núcleo (WEB-F13).

El núcleo firma con su llave privada ES256 (P-256) y el BFF solo valida con la pública: un
BFF comprometido no puede emitir sesiones. El `kid` del encabezado es la huella JWK de la
llave pública (RFC 7638), que el núcleo y el BFF calculan de la llave misma. Durante una
rotación el BFF tiene dos llaves y elige por `kid`.
"""

from __future__ import annotations

import base64
import hashlib
import json
from collections.abc import Sequence
from dataclasses import dataclass
from datetime import UTC, datetime

import jwt
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.serialization import load_pem_public_key

ALGORITMO = "ES256"
#: Quién emite los tokens. Un token de otro emisor no abre sesión aunque la firma cuadre.
EMISOR = "solventa-nucleo"
#: Solo el token de acceso abre una sesión; el de refresco nunca viaja al BFF como tal.
TIPO_ACCESO = "acceso"
#: Holgura para relojes desfasados entre el núcleo y el BFF.
TOLERANCIA_SEGUNDOS = 5


class TokenInvalido(Exception):
    """El token no se pudo validar. El motivo es para el log; al canal le llega un 401."""


class LlaveInvalida(Exception):
    """Una llave configurada no es una PEM pública P-256."""


def _b64url(numero: int) -> str:
    return base64.urlsafe_b64encode(numero.to_bytes(32, "big")).rstrip(b"=").decode()


def huella_jwk(llave: ec.EllipticCurvePublicKey) -> str:
    """Huella JWK de una llave P-256 (RFC 7638): SHA-256 del JWK canónico, en base64url.

    El JWK canónico tiene solo los miembros obligatorios, en orden lexicográfico y sin
    espacios: `{"crv","kty","x","y"}`.
    """
    numeros = llave.public_numbers()
    canonico = json.dumps(
        {"crv": "P-256", "kty": "EC", "x": _b64url(numeros.x), "y": _b64url(numeros.y)},
        separators=(",", ":"),
        sort_keys=True,
    )
    resumen = hashlib.sha256(canonico.encode()).digest()
    return base64.urlsafe_b64encode(resumen).rstrip(b"=").decode()


def cargar_llave_publica(pem: str) -> ec.EllipticCurvePublicKey:
    try:
        llave = load_pem_public_key(pem.encode())
    except ValueError as error:
        raise LlaveInvalida("la llave no es una PEM pública válida") from error
    if not isinstance(llave, ec.EllipticCurvePublicKey) or llave.curve.name != "secp256r1":
        raise LlaveInvalida("la llave de los tokens debe ser EC P-256 (ES256)")
    return llave


@dataclass(frozen=True)
class Reclamos:
    """Lo que el BFF necesita saber de una sesión válida."""

    id_cliente: str
    canal: str
    expira_en: datetime


class VerificadorDeTokens:
    def __init__(self, llaves: Sequence[ec.EllipticCurvePublicKey] = ()) -> None:
        self._por_kid = {huella_jwk(llave): llave for llave in llaves}

    @classmethod
    def desde_pem(cls, pems: Sequence[str]) -> VerificadorDeTokens:
        return cls([cargar_llave_publica(pem) for pem in pems])

    @property
    def configurado(self) -> bool:
        return bool(self._por_kid)

    @property
    def kids(self) -> tuple[str, ...]:
        return tuple(self._por_kid)

    def verificar(self, token: str) -> Reclamos:
        try:
            encabezado = jwt.get_unverified_header(token)
        except jwt.PyJWTError as error:
            raise TokenInvalido("token mal formado") from error
        if encabezado.get("alg") != ALGORITMO:
            raise TokenInvalido(f"algoritmo no admitido: {encabezado.get('alg')}")
        llave = self._por_kid.get(str(encabezado.get("kid")))
        if llave is None:
            raise TokenInvalido("kid desconocido")
        try:
            datos = jwt.decode(
                token,
                llave,
                algorithms=[ALGORITMO],
                issuer=EMISOR,
                leeway=TOLERANCIA_SEGUNDOS,
                options={"require": ["exp", "iat", "sub", "iss"]},
            )
        except jwt.PyJWTError as error:
            raise TokenInvalido(type(error).__name__) from error
        if datos.get("tipo") != TIPO_ACCESO:
            raise TokenInvalido("no es un token de acceso")
        return Reclamos(
            id_cliente=str(datos["sub"]),
            canal=str(datos.get("canal", "")),
            expira_en=datetime.fromtimestamp(int(datos["exp"]), UTC),
        )
