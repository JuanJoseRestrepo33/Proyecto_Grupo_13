#!/usr/bin/env python3
"""Prueba de humo de un servicio recién desplegado en Cloud Run (ARQ-04, WEB-F01).

Pide las rutas de RUTAS que corresponden al servicio y comprueba el estado HTTP de cada una.
Reintenta mientras la revisión nueva termina de arrancar. Nunca imprime cuerpos de respuesta,
credenciales ni tokens, porque podrían revelar detalles internos: solo método, ruta y estado.

Cada parte Cloud del sprint agrega aquí sus filas; el pipeline no cambia. Una fila `pendiente`
se escribió antes que el código que la cumple: se prueba igual, pero su falla solo avisa
(::warning::) y no detiene el despliegue. Al fusionarse la historia que nombra, se le quita.

Una fila `autenticada` se pide con la sesión de la cuenta de prueba de *staging*, cuyas
credenciales llegan en HUMO_CORREO y HUMO_CLAVE (secretos del environment). Sin ellas, la fila
se omite con un aviso.

Uso: humo.py --servicio <nucleo|bff|web> --url <URL del servicio>
"""

import argparse
import base64
import hashlib
import hmac
import json
import os
import secrets
import sys
import time
import urllib.error
import urllib.request
from collections.abc import Callable
from dataclasses import dataclass

# Máximo que se lee de un cuerpo de respuesta; las rutas de humo responden poco.
LIMITE_CUERPO = 64 * 1024


@dataclass(frozen=True)
class Respuesta:
    estado: int | None
    cuerpo: bytes = b""


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
    # Se pide con el token de la cuenta de prueba (router móvil).
    autenticada: bool = False
    # Comprueba el cuerpo de una respuesta esperada; devuelve el problema o None.
    validar: Callable[[bytes], str | None] | None = None


def _ruta(
    servicio: str,
    metodo: str,
    ruta: str,
    *esperados: int,
    encabezados: dict[str, str] | None = None,
    **opciones,
) -> Ruta:
    return Ruta(
        servicio, metodo, ruta, frozenset(esperados), tuple((encabezados or {}).items()), **opciones
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
FINALIDADES = {"cotizacion", "suscripcion", "prevencion_fraude"}


def _tres_finalidades(cuerpo: bytes) -> str | None:
    try:
        codigos = {f["codigo"] for f in json.loads(cuerpo)}
    except (ValueError, TypeError, KeyError):
        return "el catálogo no es una lista de finalidades"
    faltan = FINALIDADES - codigos
    return f"faltan finalidades: {', '.join(sorted(faltan))}" if faltan else None


def _alguna_vigente(cuerpo: bytes) -> str | None:
    try:
        estados = [p["estado"] for p in json.loads(cuerpo)]
    except (ValueError, TypeError, KeyError):
        return "la respuesta no es una lista de pólizas"
    return None if "vigente" in estados else "la cuenta de prueba no tiene pólizas vigentes"


# ID que no existe, para probar que las rutas con segmento variable piden sesión antes de buscar.
_INEXISTENTE = "humo-no-existe"
_MOVIL = "/api/v1/movil"

# 401 cuenta como éxito en las rutas protegidas: prueba que la ruta existe y que exige sesión.
RUTAS = (
    _ruta("nucleo", "GET", "/v1/", 200),
    _ruta("bff", "GET", "/salud", 200),
    _ruta("bff", "GET", "/api/v1/web/cotizaciones/productos", 200, 401),
    # WEB-F13 (#155): /sesion rechaza a quien no tiene un token válido, en los dos routers.
    _ruta("bff", "GET", "/api/v1/web/sesion", 401, caso="sin cookie"),
    _ruta(
        "bff",
        "GET",
        "/api/v1/web/sesion",
        401,
        encabezados={"Cookie": f"sesion={_AJENO}"},
        caso="token ajeno",
    ),
    _ruta("bff", "GET", f"{_MOVIL}/sesion", 401, caso="sin token"),
    _ruta(
        "bff",
        "GET",
        f"{_MOVIL}/sesion",
        401,
        encabezados={"Authorization": f"Bearer {_AJENO}"},
        caso="token ajeno",
    ),
    # WEB-F13 (#154): la verificación de identidad del router móvil existe y exige sesión.
    *(
        _ruta("bff", metodo, f"{_MOVIL}/identidad/verificacion", 401, caso="sin token")
        for metodo in ("GET", "POST")
    ),
    # MOB-F02 (#161): las rutas de consentimiento del router móvil existen y exigen sesión.
    *(
        _ruta("bff", metodo, f"{_MOVIL}{ruta}", 401, caso="sin token")
        for metodo, ruta in (
            ("GET", "/consentimientos/finalidades"),
            ("GET", "/consentimientos"),
            ("POST", "/consentimientos"),
            ("POST", f"/consentimientos/{_INEXISTENTE}/revocacion"),
            ("POST", f"/consentimientos/{_INEXISTENTE}/renovacion"),
        )
    ),
    # Con la sesión de la cuenta de prueba, el catálogo llega del núcleo con sus tres finalidades.
    _ruta(
        "bff",
        "GET",
        f"{_MOVIL}/consentimientos/finalidades",
        200,
        caso="con sesión",
        autenticada=True,
        validar=_tres_finalidades,
    ),
    # WEB-F05 (#170) y MOB-F05 (#177): pólizas, siniestros y evidencias exigen sesión en los dos
    # routers. El humo no crea siniestros; el reporte completo lo prueban los E2E.
    *(
        _ruta("bff", metodo, f"{prefijo}{ruta}", 401, caso=caso)
        for prefijo, caso in (("/api/v1/web", "sin cookie"), (_MOVIL, "sin token"))
        for metodo, ruta in (
            ("GET", "/polizas"),
            ("GET", "/siniestros"),
            ("POST", "/siniestros"),
            ("GET", f"/siniestros/{_INEXISTENTE}"),
            ("GET", f"/siniestros/{_INEXISTENTE}/evidencias"),
            ("POST", f"/siniestros/{_INEXISTENTE}/evidencias"),
        )
    ),
    # La cuenta de prueba ve las pólizas sembradas (POLIZAS_SEMILLA), con al menos una vigente.
    _ruta(
        "bff",
        "GET",
        f"{_MOVIL}/polizas",
        200,
        caso="con sesión",
        autenticada=True,
        validar=_alguna_vigente,
    ),
    # A través del proxy de nginx: un 502 o 504 significa que la web no llega al BFF.
    _ruta("web", "GET", "/", 200),
    _ruta("web", "GET", "/api/v1/web/cotizaciones/productos", 200, 401),
)

SERVICIOS = sorted({r.servicio for r in RUTAS})

Pedir = Callable[[str, str, dict[str, str], bytes | None], Respuesta]


def pedir(
    metodo: str, url: str, encabezados: dict[str, str], datos: bytes | None = None
) -> Respuesta:
    """Hace la solicitud y devuelve el estado y un cuerpo acotado; sin respuesta, estado None."""
    solicitud = urllib.request.Request(url, data=datos, method=metodo, headers=encabezados)
    try:
        with urllib.request.urlopen(solicitud, timeout=10) as respuesta:
            return Respuesta(respuesta.status, respuesta.read(LIMITE_CUERPO))
    except urllib.error.HTTPError as error:
        return Respuesta(error.code)
    except (urllib.error.URLError, TimeoutError):
        return Respuesta(None)


@dataclass(frozen=True)
class Credenciales:
    correo: str
    clave: str

    def __repr__(self) -> str:  # nunca se imprimen, ni por accidente
        return "Credenciales(***)"


def credenciales_del_ambiente(entorno: dict[str, str] | None = None) -> Credenciales | None:
    entorno = os.environ if entorno is None else entorno
    correo, clave = entorno.get("HUMO_CORREO", ""), entorno.get("HUMO_CLAVE", "")
    return Credenciales(correo, clave) if correo and clave else None


def iniciar_sesion(base: str, credenciales: Credenciales, pedir: Pedir) -> str | None:
    """Token de acceso de la cuenta de prueba por el router móvil; None si no se pudo."""
    datos = json.dumps({"correo": credenciales.correo, "contrasena": credenciales.clave}).encode()
    respuesta = pedir(
        "POST", f"{base}{_MOVIL}/sesiones", {"Content-Type": "application/json"}, datos
    )
    if respuesta.estado != 200:
        return None
    try:
        return json.loads(respuesta.cuerpo)["token_acceso"]
    except (ValueError, KeyError, TypeError):
        return None


@dataclass(frozen=True)
class Resultado:
    errores: list[str]
    avisos: list[str]


def fallas(
    servicio: str,
    url: str,
    pedir: Pedir = pedir,
    credenciales: Credenciales | None = None,
) -> Resultado:
    base = url.rstrip("/")
    errores, avisos = [], []
    rutas = [r for r in RUTAS if r.servicio == servicio]
    token = None
    if credenciales and any(r.autenticada for r in rutas):
        token = iniciar_sesion(base, credenciales, pedir)

    for r in rutas:
        caso = f" ({r.caso})" if r.caso else ""
        etiqueta = f"{r.metodo} {r.ruta}{caso}"
        destino = errores if not r.pendiente else avisos
        sufijo = f" [pendiente de {r.pendiente}]" if r.pendiente else ""
        encabezados = dict(r.encabezados)
        if r.autenticada:
            if not credenciales:
                avisos.append(f"{etiqueta}: se omite, faltan HUMO_CORREO y HUMO_CLAVE")
                continue
            if not token:
                destino.append(f"{etiqueta}: no se pudo iniciar sesión{sufijo}")
                continue
            encabezados["Authorization"] = f"Bearer {token}"

        respuesta = pedir(r.metodo, base + r.ruta, encabezados, None)
        if respuesta.estado not in r.esperados:
            esperado = " o ".join(str(e) for e in sorted(r.esperados))
            obtenido = "sin respuesta" if respuesta.estado is None else str(respuesta.estado)
            destino.append(f"{etiqueta}: se esperaba {esperado}, llegó {obtenido}{sufijo}")
        elif r.validar and (problema := r.validar(respuesta.cuerpo)):
            destino.append(f"{etiqueta}: {problema}{sufijo}")
    return Resultado(errores, avisos)


def ejecutar(
    servicio: str,
    url: str,
    intentos: int = 10,
    espera: float = 6,
    pedir: Pedir = pedir,
    dormir: Callable[[float], None] = time.sleep,
    credenciales: Credenciales | None = None,
) -> Resultado:
    """Repite la ronda completa hasta que las rutas no pendientes respondan lo esperado."""
    resultado = Resultado([], [])
    for intento in range(intentos):
        resultado = fallas(servicio, url, pedir, credenciales)
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

    resultado = ejecutar(
        args.servicio,
        args.url,
        args.intentos,
        args.espera,
        pedir,
        credenciales=credenciales_del_ambiente(),
    )
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
