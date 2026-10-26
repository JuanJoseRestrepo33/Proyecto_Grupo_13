"""Piezas compartidas por las pruebas del módulo de cotización."""

from __future__ import annotations

from datetime import UTC, datetime

import pytest

from app.compartido.reloj import RelojFijo
from app.modulos.cotizacion.aplicacion.servicio import ServicioDeCotizacion
from app.modulos.cotizacion.dominio.modelo import DatosAsegurado
from app.modulos.cotizacion.infraestructura.adaptadores_simulados import (
    ConsentimientoSimulado,
    PerfilamientoSimulado,
    RegistroDeDecisionesEnMemoria,
)
from app.modulos.cotizacion.infraestructura.repositorio_memoria import RepositorioEnMemoria

CLIENTE = "CLI-0001"
CONSENTIMIENTO = "CONSENT-2026-00417"
INSTANTE_INICIAL = datetime(2026, 10, 12, 15, 0, 0, tzinfo=UTC)


@pytest.fixture
def reloj() -> RelojFijo:
    return RelojFijo(INSTANTE_INICIAL)


@pytest.fixture
def consentimiento() -> ConsentimientoSimulado:
    return ConsentimientoSimulado()


@pytest.fixture
def auditoria() -> RegistroDeDecisionesEnMemoria:
    return RegistroDeDecisionesEnMemoria()


@pytest.fixture
def perfilamiento() -> PerfilamientoSimulado:
    return PerfilamientoSimulado()


@pytest.fixture
def servicio(reloj, consentimiento, perfilamiento, auditoria) -> ServicioDeCotizacion:
    return ServicioDeCotizacion(
        repositorio=RepositorioEnMemoria(),
        consentimiento=consentimiento,
        perfilamiento=perfilamiento,
        auditoria=auditoria,
        reloj=reloj,
        decisiones=auditoria,
    )


@pytest.fixture
def asegurado() -> DatosAsegurado:
    return DatosAsegurado(edad=34, ciudad="Bogotá", valor_asegurado=60_000_000)
