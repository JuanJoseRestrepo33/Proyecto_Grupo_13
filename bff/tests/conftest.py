"""Piezas compartidas por las pruebas del BFF: un núcleo falso y la app armada contra él.

El núcleo falso reemplaza la red con `httpx.MockTransport`: responde lo que cada prueba le
programe y recuerda lo que le pidieron, para afirmar sobre la traducción en las dos
direcciones sin levantar el núcleo.
"""

from __future__ import annotations

import json
from collections.abc import Callable

import httpx
import pytest
from fastapi.testclient import TestClient

from app.comun.configuracion import Configuracion
from app.main import crear_app

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


@pytest.fixture
def nucleo() -> NucleoFalso:
    return NucleoFalso()


@pytest.fixture
def configuracion() -> Configuracion:
    return Configuracion(nucleo_url="http://nucleo.prueba")


@pytest.fixture
def cliente(nucleo, configuracion) -> TestClient:
    return TestClient(crear_app(configuracion, nucleo.transporte))
