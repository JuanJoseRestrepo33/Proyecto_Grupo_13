"""Tokens de acceso firmados con ES256 (P-256).

El núcleo firma con la llave privada (`TOKENS_LLAVE_PRIVADA`) y valida con su pública; el
BFF solo tiene la pública, así que puede validar sesiones pero no emitirlas. El `kid` del
encabezado es la huella JWK de la llave pública (RFC 7638): el núcleo y el BFF la calculan
de la llave misma, sin saber de qué versión del secreto vino.

Reclamos: `sub` (cliente), `iss` (`solventa-nucleo`), `iat`, `exp`, `tipo` (`acceso`) y
`canal`. El BFF rechaza un token de otro canal: la cookie de la web no sirve en la app.
"""

from __future__ import annotations

import base64
import hashlib
import json
from datetime import UTC, datetime, timedelta

import jwt
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

from ..dominio.sesion import ReclamosDeAcceso, SesionNoVigenteError, TokenEmitido

ALGORITMO = "ES256"
EMISOR = "solventa-nucleo"
TIPO_ACCESO = "acceso"
TOLERANCIA = timedelta(seconds=5)


class LlaveInvalidaError(ValueError):
    """La llave configurada no es una PEM P-256 utilizable."""


def _b64url(datos: bytes) -> str:
    return base64.urlsafe_b64encode(datos).rstrip(b"=").decode()


def huella_jwk(llave: ec.EllipticCurvePublicKey) -> str:
    """SHA-256 del JWK canónico `{"crv","kty","x","y"}`, en base64url sin relleno."""
    numeros = llave.public_numbers()
    canonico = json.dumps(
        {
            "crv": "P-256",
            "kty": "EC",
            "x": _b64url(numeros.x.to_bytes(32, "big")),
            "y": _b64url(numeros.y.to_bytes(32, "big")),
        },
        separators=(",", ":"),
        sort_keys=True,
    )
    return _b64url(hashlib.sha256(canonico.encode()).digest())


def _exigir_p256(llave: object) -> None:
    curva = getattr(llave, "curve", None)
    if getattr(curva, "name", None) != "secp256r1":
        raise LlaveInvalidaError("la llave de los tokens debe ser EC P-256 (ES256)")


def cargar_privada(pem: str) -> ec.EllipticCurvePrivateKey:
    try:
        llave = serialization.load_pem_private_key(pem.encode(), password=None)
    except (ValueError, TypeError) as error:
        raise LlaveInvalidaError("TOKENS_LLAVE_PRIVADA no es una PEM privada válida") from error
    _exigir_p256(llave)
    return llave  # type: ignore[return-value]


def cargar_publica(pem: str) -> ec.EllipticCurvePublicKey:
    try:
        llave = serialization.load_pem_public_key(pem.encode())
    except ValueError as error:
        raise LlaveInvalidaError("TOKENS_LLAVE_PUBLICA no es una PEM pública válida") from error
    _exigir_p256(llave)
    return llave  # type: ignore[return-value]


class EmisorEs256:
    """`efimera` marca una llave generada al arrancar, solo para desarrollo local: los
    tokens que firma no los acepta ningún BFF que no la conozca."""

    def __init__(self, privada: ec.EllipticCurvePrivateKey, efimera: bool = False) -> None:
        _exigir_p256(privada)
        self._privada = privada
        self._publica = privada.public_key()
        self.kid = huella_jwk(self._publica)
        self.efimera = efimera

    @classmethod
    def desde_pem(cls, pem: str) -> EmisorEs256:
        return cls(cargar_privada(pem))

    @classmethod
    def efimero(cls) -> EmisorEs256:
        return cls(ec.generate_private_key(ec.SECP256R1()), efimera=True)

    def corresponde_a(self, pem_publica: str) -> bool:
        """Si una pública configurada aparte es la de esta privada."""
        return huella_jwk(cargar_publica(pem_publica)) == self.kid

    def pem_publica(self) -> str:
        return self._publica.public_bytes(
            serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo
        ).decode()

    def emitir_acceso(
        self, id_cliente: str, canal: str, ahora: datetime, vigencia: timedelta
    ) -> TokenEmitido:
        expira_en = ahora + vigencia
        token = jwt.encode(
            {
                "sub": id_cliente,
                "iss": EMISOR,
                "iat": int(ahora.timestamp()),
                "exp": int(expira_en.timestamp()),
                "tipo": TIPO_ACCESO,
                "canal": canal,
            },
            self._privada,
            algorithm=ALGORITMO,
            headers={"kid": self.kid},
        )
        return TokenEmitido(token=token, expira_en=expira_en.replace(microsecond=0))

    def validar_acceso(self, token: str, ahora: datetime) -> ReclamosDeAcceso:
        """Valida firma, emisor, tipo y vigencia contra el reloj del núcleo."""
        try:
            encabezado = jwt.get_unverified_header(token)
            if encabezado.get("kid") != self.kid:
                raise SesionNoVigenteError("token firmado con otra llave")
            datos = jwt.decode(
                token,
                self._publica,
                algorithms=[ALGORITMO],
                issuer=EMISOR,
                options={
                    "require": ["exp", "iat", "sub", "iss"],
                    "verify_exp": False,
                    "verify_iat": False,
                },
            )
        except jwt.PyJWTError as error:
            raise SesionNoVigenteError("token no válido") from error
        expira_en = datetime.fromtimestamp(int(datos["exp"]), UTC)
        if ahora >= expira_en + TOLERANCIA:
            raise SesionNoVigenteError("la sesión venció")
        if datos.get("tipo") != TIPO_ACCESO:
            raise SesionNoVigenteError("no es un token de acceso")
        return ReclamosDeAcceso(
            id_cliente=str(datos["sub"]), canal=str(datos.get("canal", "")), expira_en=expira_en
        )
