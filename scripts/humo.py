#!/usr/bin/env python3
"""Prueba de humo de un servicio recién desplegado en Cloud Run (ARQ-04, WEB-F01).

Pide las rutas de RUTAS que corresponden al servicio y comprueba el estado HTTP de cada una.
Reintenta mientras la revisión nueva termina de arrancar. Nunca imprime cuerpos de respuesta,
porque podrían revelar detalles internos: solo método, ruta y estado.

Cada parte Cloud del sprint agrega aquí sus filas; el pipeline no cambia. Una fila `pendiente`
se escribió antes que el código que la cumple: se prueba igual, pero su falla solo avisa
(::warning::) y no detiene el despliegue. Al fusionarse la historia que nombra, se le quita.

Uso: humo.py --servicio <nucleo|bff|web> --url <URL del servicio>
"""

import argparse
import base64
import hashlib
import hmac
import json
import secrets
import sys
import time
import urllib.error
import urllib.request
from collections.abc import Callable
from dataclasses import dataclass


@dataclass(frozen=True)
class Ruta:
    servicio: str
    metodo: str
    ruta: str
    esperados: frozenset[int]
    encabezados: tuple[tuple[str, str], ...] = ()
    # Issue que entrega la ruta; mientras no esté fusionado, la falla es un aviso.
    pendiente: str | None = None
    # Para distinguir dos filas sobre la misma ruta en los mensajes.
    caso: str = ""


def _ruta(
    servicio: str,
    metodo: str,
    ruta: str,
    *esperados: int,
    encabezados: dict[str, str] | None = None,
    pendiente: str | None = None,
    caso: str = "",
) -> Ruta:
    return Ruta(
        servicio,
        metodo,
        ruta,
        frozenset(esperados),
        tuple((encabezados or {}).items()),
        pendiente,
        caso,
    )


def _b64(datos: bytes) -> str:
    return base64.urlsafe_b64encode(datos).rstrip(b"=").decode()


def token_ajeno() -> str:
    """JWT bien formado pero firmado con una llave que nadie más conoce (y con HS256).

    El BFF valida con la llave pública ES256 del núcleo, así que debe rechazarlo con 401. Un 5xx
    significa que la llave falta o está mal montada; un 200, que acepta firmas ajenas.
    """
    encabezado = _b64(json.dumps({"alg": "HS256", "typ": "JWT", "kid": "humo-ajena"}).encode())
    carga = _b64(json.dumps({"sub": "humo", "exp": int(time.time()) + 300}).encode())
    firmado = f"{encabezado}.{carga}".encode()
    firma = _b64(hmac.new(secrets.token_bytes(32), firmado, hashlib.sha256).digest())
    return f"{encabezado}.{carga}.{firma}"


_AJENO = token_ajeno()

# 401 cuenta como éxito en las rutas protegidas: prueba que la ruta existe y que exige sesión.
RUTAS = (
    _ruta("nucleo", "GET", "/v1/", 200),
    _ruta("bff", "GET", "/salud", 200),
    _ruta("bff", "GET", "/api/v1/web/cotizaciones/productos", 200, 401),
    # WEB-F13 (#155): /sesion rechaza a quien no tiene un token válido, en los dos routers.
    _ruta("bff", "GET", "/api/v1/web/sesion", 401, pendiente="#152", caso="sin cookie"),
    _ruta(
        "bff",
        "GET",
        "/api/v1/web/sesion",
        401,
        encabezados={"Cookie": f"sesion={_AJENO}"},
        pendiente="#152",
        caso="token ajeno",
    ),
    _ruta("bff", "GET", "/api/v1/movil/sesion", 401, pendiente="#152", caso="sin token"),
    _ruta(
        "bff",
        "GET",
        "/api/v1/movil/sesion",
        401,
        encabezados={"Authorization": f"Bearer {_AJENO}"},
        pendiente="#152",
        caso="token ajeno",
    ),
    # A través del proxy de nginx: un 502 o 504 significa que la web no llega al BFF.
    _ruta("web", "GET", "/", 200),
    _ruta("web", "GET", "/api/v1/web/cotizaciones/productos", 200, 401),
)

SERVICIOS = sorted({r.servicio for r in RUTAS})

Pedir = Callable[[str, str, dict[str, str]], int | None]


def pedir(metodo: str, url: str, encabezados: dict[str, str]) -> int | None:
    """Devuelve el estado HTTP, o None si no hubo respuesta. Descarta el cuerpo sin leerlo."""
    solicitud = urllib.request.Request(url, method=metodo, headers=encabezados)
    try:
        with urllib.request.urlopen(solicitud, timeout=10) as respuesta:
            return respuesta.status
    except urllib.error.HTTPError as error:
        return error.code
    except (urllib.error.URLError, TimeoutError):
        return None


@dataclass(frozen=True)
class Resultado:
    errores: list[str]
    avisos: list[str]


def fallas(servicio: str, url: str, pedir: Pedir = pedir) -> Resultado:
    base = url.rstrip("/")
    errores, avisos = [], []
    for r in RUTAS:
        if r.servicio != servicio:
            continue
        estado = pedir(r.metodo, base + r.ruta, dict(r.encabezados))
        if estado in r.esperados:
            continue
        esperado = " o ".join(str(e) for e in sorted(r.esperados))
        obtenido = "sin respuesta" if estado is None else str(estado)
        caso = f" ({r.caso})" if r.caso else ""
        mensaje = f"{r.metodo} {r.ruta}{caso}: se esperaba {esperado}, llegó {obtenido}"
        if r.pendiente:
            avisos.append(f"{mensaje} [pendiente de {r.pendiente}]")
        else:
            errores.append(mensaje)
    return Resultado(errores, avisos)


def ejecutar(
    servicio: str,
    url: str,
    intentos: int = 10,
    espera: float = 6,
    pedir: Pedir = pedir,
    dormir: Callable[[float], None] = time.sleep,
) -> Resultado:
    """Repite la ronda completa hasta que las rutas no pendientes respondan lo esperado."""
    resultado = Resultado([], [])
    for intento in range(intentos):
        resultado = fallas(servicio, url, pedir)
        if not resultado.errores:
            return resultado
        if intento < intentos - 1:
            dormir(espera)
    return resultado


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--servicio", required=True, choices=SERVICIOS)
    parser.add_argument("--url", required=True)
    parser.add_argument("--intentos", type=int, default=10)
    parser.add_argument("--espera", type=float, default=6)
    args = parser.parse_args(argv)

    resultado = ejecutar(args.servicio, args.url, args.intentos, args.espera, pedir)
    for aviso in resultado.avisos:
        print(f"::warning::Humo de {args.servicio}: {aviso}")
    for error in resultado.errores:
        print(f"::error::Humo de {args.servicio}: {error}")
    if resultado.errores:
        return 1
    rutas = sum(1 for r in RUTAS if r.servicio == args.servicio)
    print(f"Humo de {args.servicio}: {rutas} rutas probadas, sin errores")
    return 0


if __name__ == "__main__":
    sys.exit(main())
