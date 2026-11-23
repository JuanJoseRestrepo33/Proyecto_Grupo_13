"""Contraseñas con hash y sal (WEB-F13, criterio 2; absorbe el pendiente de MOB-F01).

La contraseña nunca se guarda ni se registra en claro: se guarda su derivación con scrypt,
una función de memoria dura, con una sal aleatoria por cuenta. La credencial guardada dice
con qué parámetros se derivó, así que subirlos más adelante no invalida las existentes.

    scrypt$<n>$<r>$<p>$<sal en base64url>$<derivación en base64url>

Comparar en tiempo constante y verificar también cuando la cuenta no existe (contra una
credencial señuelo) evita que el tiempo de respuesta revele qué correos están registrados.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import re
import secrets
from functools import cache

from app.compartido.errores import DatoInvalido

ALGORITMO = "scrypt"
#: Costo de scrypt: 2^14 iteraciones de 8 bloques usan 16 MB por derivación, unos 50 ms.
COSTO_N, BLOQUES_R, PARALELISMO_P = 2**14, 8, 1
LARGO_SAL, LARGO_DERIVACION = 16, 32

LARGO_MINIMO, LARGO_MAXIMO = 8, 64
_LETRA = re.compile(r"[^\W\d_]")
_DIGITO = re.compile(r"\d")


class ContrasenaDebilError(DatoInvalido):
    codigo = "contrasena_debil"


def exigir_contrasena_valida(contrasena: str) -> None:
    """La misma regla del contrato móvil: de 8 a 64 caracteres, con al menos una letra y
    un número."""
    if not LARGO_MINIMO <= len(contrasena) <= LARGO_MAXIMO:
        raise ContrasenaDebilError(
            f"la contraseña debe tener entre {LARGO_MINIMO} y {LARGO_MAXIMO} caracteres"
        )
    if not (_LETRA.search(contrasena) and _DIGITO.search(contrasena)):
        raise ContrasenaDebilError("la contraseña debe tener al menos una letra y un número")


def _b64(datos: bytes) -> str:
    return base64.urlsafe_b64encode(datos).rstrip(b"=").decode()


def _de_b64(texto: str) -> bytes:
    return base64.urlsafe_b64decode(texto + "=" * (-len(texto) % 4))


def _derivar(contrasena: str, sal: bytes, n: int, r: int, p: int) -> bytes:
    return hashlib.scrypt(
        contrasena.encode(),
        salt=sal,
        n=n,
        r=r,
        p=p,
        maxmem=256 * n * r + 1024 * 1024,
        dklen=LARGO_DERIVACION,
    )


def derivar(contrasena: str) -> str:
    """La credencial que se guarda: algoritmo, parámetros, sal y derivación."""
    sal = secrets.token_bytes(LARGO_SAL)
    derivada = _derivar(contrasena, sal, COSTO_N, BLOQUES_R, PARALELISMO_P)
    return f"{ALGORITMO}${COSTO_N}${BLOQUES_R}${PARALELISMO_P}${_b64(sal)}${_b64(derivada)}"


def verificar(contrasena: str, credencial: str) -> bool:
    """Si la contraseña corresponde a la credencial. Una credencial ilegible nunca coincide."""
    try:
        algoritmo, n, r, p, sal, derivada = credencial.split("$")
        if algoritmo != ALGORITMO:
            return False
        calculada = _derivar(contrasena, _de_b64(sal), int(n), int(r), int(p))
    except ValueError:
        return False
    return hmac.compare_digest(calculada, _de_b64(derivada))


@cache
def credencial_senuelo() -> str:
    """Credencial con la que se compara cuando el correo no existe, para que tarde lo mismo."""
    return derivar(secrets.token_urlsafe(16))
