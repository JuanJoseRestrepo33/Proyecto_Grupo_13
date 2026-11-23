"""Traducción de los errores del kernel a estados HTTP en la fachada."""

from __future__ import annotations

import pytest
from fastapi import APIRouter
from fastapi.testclient import TestClient

from app.compartido.errores import (
    DatoInvalido,
    DemasiadasSolicitudes,
    DependenciaNoDisponible,
    ErrorDeNegocio,
    NoAutenticado,
    RecursoNoEncontrado,
    ReglaDeNegocioViolada,
)
from app.compartido.modulo import DescriptorDeModulo
from app.main import crear_app

router = APIRouter(prefix="/errores")
ERRORES: dict[str, ErrorDeNegocio] = {
    "no_autenticado": NoAutenticado("credenciales inválidas"),
    "no_encontrado": RecursoNoEncontrado("no existe"),
    "regla": ReglaDeNegocioViolada("no aplica"),
    "dato": DatoInvalido("código incorrecto", intentos_restantes=2),
    "frecuencia": DemasiadasSolicitudes("muy pronto", reintentar_en_segundos=17),
    "frecuencia_sin_espera": DemasiadasSolicitudes("muy pronto"),
    "dependencia": DependenciaNoDisponible("caído"),
    "generico": ErrorDeNegocio("otro"),
}


@router.get("/{nombre}")
def lanzar(nombre: str) -> None:
    raise ERRORES[nombre]


@pytest.fixture
def cliente() -> TestClient:
    modulo = DescriptorDeModulo(
        nombre="errores", descripcion="Solo para pruebas", historia="-", router=router
    )
    return TestClient(crear_app([modulo]))


@pytest.mark.parametrize(
    ("nombre", "estado", "codigo"),
    [
        ("no_autenticado", 401, "no_autenticado"),
        ("no_encontrado", 404, "recurso_no_encontrado"),
        ("regla", 409, "regla_de_negocio_violada"),
        ("dato", 422, "dato_invalido"),
        ("frecuencia", 429, "demasiadas_solicitudes"),
        ("dependencia", 503, "dependencia_no_disponible"),
        ("generico", 400, "error_de_negocio"),
    ],
)
def test_cada_error_del_kernel_tiene_su_estado(cliente, nombre, estado, codigo):
    respuesta = cliente.get(f"/v1/errores/{nombre}")

    assert respuesta.status_code == estado
    assert respuesta.headers["content-type"] == "application/problem+json"
    assert respuesta.json()["title"] == codigo


def test_los_detalles_viajan_en_el_problema(cliente):
    assert cliente.get("/v1/errores/dato").json()["detalles"] == {"intentos_restantes": 2}


def test_la_espera_de_un_429_va_en_retry_after(cliente):
    assert cliente.get("/v1/errores/frecuencia").headers["Retry-After"] == "17"
    assert "Retry-After" not in cliente.get("/v1/errores/frecuencia_sin_espera").headers
