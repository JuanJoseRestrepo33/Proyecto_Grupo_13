"""Piezas compartidas por las pruebas del BFF: un núcleo falso y la app armada contra él.

El núcleo falso reemplaza la red con `httpx.MockTransport`: responde lo que cada prueba le
programe y recuerda lo que le pidieron, para afirmar sobre la traducción en las dos
direcciones sin levantar el núcleo.
"""

from __future__ import annotations

import json
import time
from collections.abc import Callable
from dataclasses import dataclass

import httpx
import jwt
import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi.testclient import TestClient

from app.comun.configuracion import Configuracion
from app.comun.tokens import EMISOR, huella_jwk
from app.main import crear_app
from app.web.dependencias import COOKIE_SESION

CLIENTE = "CLI-2026-000318"

Respuesta = httpx.Response | Callable[[httpx.Request], httpx.Response]


def problema(estado: int, codigo: str, mensaje: str = "", **detalles: object) -> httpx.Response:
    """Un error del núcleo, en su formato `application/problem+json`."""
    cuerpo: dict[str, object] = {"title": codigo, "status": estado, "detail": mensaje}
    if detalles:
        cuerpo["detalles"] = detalles
    return httpx.Response(
        estado, content=json.dumps(cuerpo), headers={"content-type": "application/problem+json"}
    )


class NucleoFalso:
    def __init__(self) -> None:
        self._respuestas: dict[tuple[str, str], Respuesta] = {}
        self.solicitudes: list[httpx.Request] = []
        self.responder("GET", "/salud", 200, {"estado": "ok"})

    def responder(
        self,
        metodo: str,
        ruta: str,
        estado: int | Respuesta = 200,
        cuerpo: object = None,
        encabezados: dict[str, str] | None = None,
    ) -> None:
        if isinstance(estado, int):
            respuesta: Respuesta = httpx.Response(estado, json=cuerpo, headers=encabezados)
        else:
            respuesta = estado
        self._respuestas[(metodo, ruta)] = respuesta

    def __call__(self, solicitud: httpx.Request) -> httpx.Response:
        self.solicitudes.append(solicitud)
        respuesta = self._respuestas.get((solicitud.method, solicitud.url.path))
        if respuesta is None:
            return problema(404, "recurso_no_encontrado", f"sin ruta {solicitud.url.path}")
        if callable(respuesta):
            return respuesta(solicitud)
        return respuesta

    @property
    def transporte(self) -> httpx.MockTransport:
        return httpx.MockTransport(self)

    @property
    def ultima(self) -> httpx.Request:
        return self.solicitudes[-1]

    def cuerpo_de(self, solicitud: httpx.Request) -> object:
        return json.loads(solicitud.content)


def al_nucleo(nucleo: NucleoFalso) -> list[httpx.Request]:
    """Lo que el BFF le pidió al núcleo, sin las sondas de `/salud`."""
    return [s for s in nucleo.solicitudes if s.url.path != "/salud"]


class Firmante:
    """Hace las veces del núcleo emitiendo tokens con su propia llave privada ES256."""

    def __init__(self) -> None:
        self.privada = ec.generate_private_key(ec.SECP256R1())
        self.kid = huella_jwk(self.privada.public_key())
        self.pem_publica = (
            self.privada.public_key()
            .public_bytes(
                serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo
            )
            .decode()
        )

    def emitir(
        self,
        id_cliente: str = CLIENTE,
        canal: str = "web",
        vigencia: int = 900,
        encabezado: dict[str, object] | None = None,
        **reclamos: object,
    ) -> str:
        ahora = int(time.time())
        datos = {
            "sub": id_cliente,
            "iss": EMISOR,
            "iat": ahora,
            "exp": ahora + vigencia,
            "tipo": "acceso",
            "canal": canal,
            **reclamos,
        }
        return jwt.encode(
            datos, self.privada, algorithm="ES256", headers={"kid": self.kid, **(encabezado or {})}
        )


@pytest.fixture
def nucleo() -> NucleoFalso:
    return NucleoFalso()


@pytest.fixture(scope="session")
def firmante() -> Firmante:
    return Firmante()


@pytest.fixture
def configuracion(firmante) -> Configuracion:
    return Configuracion(nucleo_url="http://nucleo.prueba", llaves_publicas=(firmante.pem_publica,))


@pytest.fixture
def cliente(nucleo, configuracion) -> TestClient:
    return TestClient(crear_app(configuracion, nucleo.transporte))


@dataclass(frozen=True)
class Canal:
    nombre: str
    prefijo: str


WEB = Canal("web", "/api/v1/web")
MOVIL = Canal("movil", "/api/v1/movil")


@pytest.fixture(params=[WEB, MOVIL], ids=lambda c: c.nombre)
def canal(request) -> Canal:
    """Por defecto la prueba corre en los dos canales; un módulo de un solo canal la redefine."""
    return request.param


@pytest.fixture
def con_sesion(cliente, firmante, canal):
    token = firmante.emitir(canal=canal.nombre)
    if canal is WEB:
        cliente.cookies.set(COOKIE_SESION, token)
    else:
        cliente.headers["Authorization"] = f"Bearer {token}"
    return cliente
