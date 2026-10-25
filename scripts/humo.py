#!/usr/bin/env python3
"""Prueba de humo de un servicio recién desplegado en Cloud Run (ARQ-04, WEB-F01).

Pide las rutas de RUTAS que corresponden al servicio y comprueba el estado HTTP de cada una.
Reintenta mientras la revisión nueva termina de arrancar. Nunca imprime cuerpos de respuesta,
porque podrían revelar detalles internos: solo método, ruta y estado.

Cada parte Cloud del sprint agrega aquí sus filas; el pipeline no cambia.

Uso: humo.py --servicio <nucleo|bff|web> --url <URL del servicio>
"""

import argparse
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


def _ruta(servicio: str, metodo: str, ruta: str, *esperados: int) -> Ruta:
    return Ruta(servicio, metodo, ruta, frozenset(esperados))


# 401 cuenta como éxito en las rutas protegidas: prueba que la ruta existe y que exige sesión.
RUTAS = (
    _ruta("nucleo", "GET", "/v1/", 200),
    _ruta("bff", "GET", "/salud", 200),
    _ruta("bff", "GET", "/api/v1/web/cotizaciones/productos", 200, 401),
    # A través del proxy de nginx: un 502 o 504 significa que la web no llega al BFF.
    _ruta("web", "GET", "/", 200),
    _ruta("web", "GET", "/api/v1/web/cotizaciones/productos", 200, 401),
)

SERVICIOS = sorted({r.servicio for r in RUTAS})

Pedir = Callable[[str, str], int | None]


def pedir(metodo: str, url: str) -> int | None:
    """Devuelve el estado HTTP, o None si no hubo respuesta. Descarta el cuerpo sin leerlo."""
    solicitud = urllib.request.Request(url, method=metodo)
    try:
        with urllib.request.urlopen(solicitud, timeout=10) as respuesta:
            return respuesta.status
    except urllib.error.HTTPError as error:
        return error.code
    except (urllib.error.URLError, TimeoutError):
        return None


def fallas(servicio: str, url: str, pedir: Pedir = pedir) -> list[str]:
    base = url.rstrip("/")
    resultado = []
    for r in RUTAS:
        if r.servicio != servicio:
            continue
        estado = pedir(r.metodo, base + r.ruta)
        if estado not in r.esperados:
            esperado = " o ".join(str(e) for e in sorted(r.esperados))
            obtenido = "sin respuesta" if estado is None else str(estado)
            resultado.append(f"{r.metodo} {r.ruta}: se esperaba {esperado}, llegó {obtenido}")
    return resultado


def ejecutar(
    servicio: str,
    url: str,
    intentos: int = 10,
    espera: float = 6,
    pedir: Pedir = pedir,
    dormir: Callable[[float], None] = time.sleep,
) -> list[str]:
    """Repite la ronda completa hasta que todas las rutas respondan lo esperado."""
    errores: list[str] = []
    for intento in range(intentos):
        errores = fallas(servicio, url, pedir)
        if not errores:
            return []
        if intento < intentos - 1:
            dormir(espera)
    return errores


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--servicio", required=True, choices=SERVICIOS)
    parser.add_argument("--url", required=True)
    parser.add_argument("--intentos", type=int, default=10)
    parser.add_argument("--espera", type=float, default=6)
    args = parser.parse_args(argv)

    errores = ejecutar(args.servicio, args.url, args.intentos, args.espera)
    for error in errores:
        print(f"::error::Humo de {args.servicio}: {error}")
    if errores:
        return 1
    rutas = sum(1 for r in RUTAS if r.servicio == args.servicio)
    print(f"Humo de {args.servicio}: {rutas} rutas responden lo esperado")
    return 0


if __name__ == "__main__":
    sys.exit(main())
