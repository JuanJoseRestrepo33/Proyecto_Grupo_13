"""Pruebas de suscripción contra PostgreSQL: una cotización se acepta una sola vez (WEB-F03).

Corren si hay una base: `SUSCRIPCION_DATABASE_URL` o, en su defecto, `AUDITORIA_DATABASE_URL`.
"""

from __future__ import annotations

import os
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, date, datetime

import psycopg
import pytest

from app.compartido.dinero import Dinero
from app.modulos.suscripcion.api import dependencias
from app.modulos.suscripcion.dominio.modelo import (
    CoberturaOfertada,
    CondicionesDeOferta,
    EstadoSuscripcion,
    Firma,
    PagoDeLaPrima,
    Paso,
    PolizaEmitida,
    Suscripcion,
)
from app.modulos.suscripcion.infraestructura.repositorio_postgres import RepositorioPostgres

DSN = os.environ.get("SUSCRIPCION_DATABASE_URL") or os.environ.get("AUDITORIA_DATABASE_URL")

pytestmark = pytest.mark.skipif(
    not DSN, reason="Define SUSCRIPCION_DATABASE_URL para probar contra PostgreSQL"
)

INSTANTE = datetime(2026, 11, 8, 15, 0, tzinfo=UTC)
CONDICIONES = CondicionesDeOferta(
    id_cotizacion="COT-1",
    producto="auto",
    nombre_producto="Seguro de Auto Todo Riesgo",
    prima_mensual=Dinero.cop(132_900),
    valor_asegurado="$60.000.000",
    deducible="5% del valor asegurado",
    coberturas=(CoberturaOfertada("Responsabilidad civil", "$1.000.000.000"),),
)


def _suscripcion(id_suscripcion: str = "SUS-1") -> Suscripcion:
    return Suscripcion(id_suscripcion, "CLI-1", CONDICIONES, "web", INSTANTE).con_paso(
        Paso.ACEPTACION, "DEC-1", INSTANTE
    )


@pytest.fixture
def repositorio() -> RepositorioPostgres:
    with psycopg.connect(DSN, autocommit=True) as conexion:
        conexion.execute("DROP SCHEMA IF EXISTS suscripcion CASCADE")
    repositorio = RepositorioPostgres(DSN)
    repositorio.preparar()
    return repositorio


def test_la_suscripcion_emitida_se_lee_igual_desde_otra_replica(repositorio):
    guardada = repositorio.guardar_si_nueva(_suscripcion())
    pago = PagoDeLaPrima(
        "PAG-1", "aprobado", "pse", Dinero.cop(132_900), "TX-1", INSTANTE, INSTANTE, "DEC-2"
    )
    emitida = (
        guardada.pagada(pago, INSTANTE)
        .firmada(Firma("FIR-1", "firma-simulada", INSTANTE), "DEC-3")
        .emitida(
            PolizaEmitida("POL-2026-000001", date(2026, 11, 8), date(2027, 11, 7), "DEC-4"),
            INSTANTE,
        )
    )
    emitida = repositorio.actualizar(emitida)

    leida = RepositorioPostgres(DSN).obtener("SUS-1")

    assert leida == emitida
    assert leida.estado is EstadoSuscripcion.EMITIDA
    assert [p.paso for p in leida.trazabilidad] == list(Paso)
    assert repositorio.por_cotizacion("COT-1") == emitida
    assert repositorio.del_cliente("CLI-1") == (emitida,)
    assert repositorio.obtener("SUS-NO") is None
    assert repositorio.por_cotizacion("COT-NO") is None


def test_dos_replicas_que_aceptan_a_la_vez_dejan_una_suscripcion(repositorio):
    def aceptar(i):
        return RepositorioPostgres(DSN).guardar_si_nueva(_suscripcion(f"SUS-{i}"))

    with ThreadPoolExecutor(6) as hilos:
        guardadas = list(hilos.map(aceptar, range(6)))

    assert len({s.id_suscripcion for s in guardadas}) == 1
    assert len(repositorio.del_cliente("CLI-1")) == 1


def test_salud_reporta_la_base_de_suscripciones(monkeypatch):
    monkeypatch.setenv(dependencias.VARIABLE_DSN, DSN)
    dependencias.reiniciar()
    try:
        estados = {e.nombre: e.estado for e in dependencias.estado_dependencias()}
    finally:
        dependencias.reiniciar()

    assert estados["repositorio_suscripciones"] == "ok"
