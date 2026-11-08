"""Pruebas de WEB-F03, criterio 2, contra PostgreSQL: sin cobro doble entre réplicas.

Corren si hay una base: `PAGOS_DATABASE_URL` o, en su defecto, `AUDITORIA_DATABASE_URL` (en el
CI, el servicio `postgres` del job `backend`).
"""

from __future__ import annotations

import os
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime

import psycopg
import pytest

from app.compartido.dinero import Dinero
from app.compartido.reloj import RelojFijo
from app.modulos.pagos.api import dependencias
from app.modulos.pagos.aplicacion.servicio import ServicioDePagos
from app.modulos.pagos.dominio.errores import PagoEnCursoError, PagoYaRegistradoError
from app.modulos.pagos.dominio.modelo import EstadoPago, MedioPago
from app.modulos.pagos.infraestructura.pasarela_simulada import PasarelaSimulada
from app.modulos.pagos.infraestructura.repositorio_postgres import RepositorioPostgres

DSN = os.environ.get("PAGOS_DATABASE_URL") or os.environ.get("AUDITORIA_DATABASE_URL")

pytestmark = pytest.mark.skipif(
    not DSN, reason="Define PAGOS_DATABASE_URL para probar contra PostgreSQL"
)

INSTANTE = datetime(2026, 11, 8, 15, 0, tzinfo=UTC)
PRIMA = Dinero.cop(132_900)


class Auditoria:
    def registrar(self, pago) -> str:
        return f"DEC-{pago.id_pago}"


@pytest.fixture
def repositorio() -> RepositorioPostgres:
    with psycopg.connect(DSN, autocommit=True) as conexion:
        conexion.execute("DROP SCHEMA IF EXISTS pagos CASCADE")
    repositorio = RepositorioPostgres(DSN)
    repositorio.preparar()
    return repositorio


def _servicio(repositorio, pasarela=None) -> ServicioDePagos:
    return ServicioDePagos(
        repositorio, pasarela or PasarelaSimulada(), Auditoria(), RelojFijo(INSTANTE)
    )


def test_el_pago_sobrevive_a_otra_instancia_del_repositorio(repositorio):
    pago, _ = _servicio(repositorio).cobrar(
        "SUS-1", "CLI-1", PRIMA, MedioPago.PSE, "clave-1", "web"
    )

    otra_replica = _servicio(RepositorioPostgres(DSN))
    repetido, nuevo = otra_replica.cobrar("SUS-1", "CLI-1", PRIMA, MedioPago.PSE, "clave-1", "web")

    assert not nuevo
    assert repetido == pago
    confirmado = otra_replica.consultar("SUS-1", pago.id_pago)
    assert confirmado.estado is EstadoPago.APROBADO
    assert confirmado.id_decision == f"DEC-{pago.id_pago}"
    assert _servicio(repositorio).ultimo("SUS-1") == confirmado


def test_dos_replicas_con_claves_distintas_cobran_una_sola_vez(repositorio):
    pasarela = PasarelaSimulada()

    def intentar(i):
        servicio = _servicio(RepositorioPostgres(DSN), pasarela)
        try:
            return servicio.cobrar(
                "SUS-1", "CLI-1", PRIMA, MedioPago.TARJETA_CREDITO, f"clave-{i}", "web"
            )[0]
        except (PagoYaRegistradoError, PagoEnCursoError):
            return None

    with ThreadPoolExecutor(6) as hilos:
        resultados = list(hilos.map(intentar, range(12)))

    assert len([r for r in resultados if r is not None]) == 1
    assert len(pasarela.cobros) == 1


def test_un_rechazo_libera_el_concepto_y_la_caida_libera_la_reserva(repositorio):
    pasarela = PasarelaSimulada(resultados={MedioPago.TARJETA_CREDITO: EstadoPago.RECHAZADO})
    servicio = _servicio(repositorio, pasarela)
    servicio.cobrar("SUS-1", "CLI-1", PRIMA, MedioPago.TARJETA_CREDITO, "clave-1", "web")

    pasarela.caida = True
    with pytest.raises(Exception, match="pasarela"):
        servicio.cobrar("SUS-1", "CLI-1", PRIMA, MedioPago.TARJETA_CREDITO, "clave-2", "web")
    pasarela.caida = False
    pasarela.resultados[MedioPago.TARJETA_CREDITO] = EstadoPago.APROBADO
    pago, nuevo = servicio.cobrar(
        "SUS-1", "CLI-1", PRIMA, MedioPago.TARJETA_CREDITO, "clave-2", "web"
    )

    assert nuevo and pago.estado is EstadoPago.APROBADO
    assert [p.estado for p in repositorio.del_concepto("SUS-1")] == [
        EstadoPago.RECHAZADO,
        EstadoPago.APROBADO,
    ]


def test_salud_reporta_la_base_de_pagos(monkeypatch):
    monkeypatch.setenv(dependencias.VARIABLE_DSN, DSN)
    dependencias.reiniciar()
    try:
        estados = {e.nombre: e.estado for e in dependencias.estado_dependencias()}
    finally:
        dependencias.reiniciar()

    assert estados["repositorio_pagos"] == "ok"
