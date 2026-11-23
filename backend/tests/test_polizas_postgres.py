"""Pruebas de pólizas contra PostgreSQL: emisión única por suscripción y semilla (WEB-F03).

Corren si hay una base: `POLIZAS_DATABASE_URL` o, en su defecto, `AUDITORIA_DATABASE_URL`.
"""

from __future__ import annotations

import os
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
from datetime import UTC, date, datetime

import psycopg
import pytest

from app.compartido.reloj import RelojFijo
from app.modulos.polizas.api import dependencias
from app.modulos.polizas.aplicacion.servicio import ServicioDePolizas
from app.modulos.polizas.dominio.modelo import Poliza
from app.modulos.polizas.infraestructura.almacen_documentos import AlmacenDeDocumentosEnMemoria
from app.modulos.polizas.infraestructura.documento_pdf import GeneradorPdf
from app.modulos.polizas.infraestructura.repositorio_postgres import RepositorioPostgres

from .test_emision_polizas import CLIENTE, SOLICITUD

DSN = os.environ.get("POLIZAS_DATABASE_URL") or os.environ.get("AUDITORIA_DATABASE_URL")

pytestmark = pytest.mark.skipif(
    not DSN, reason="Define POLIZAS_DATABASE_URL para probar contra PostgreSQL"
)

INSTANTE = datetime(2026, 11, 8, 15, 0, tzinfo=UTC)


class Auditoria:
    def registrar_emision(self, poliza) -> str:
        return f"DEC-{poliza.numero}"


@pytest.fixture
def repositorio() -> RepositorioPostgres:
    with psycopg.connect(DSN, autocommit=True) as conexion:
        conexion.execute("DROP SCHEMA IF EXISTS polizas CASCADE")
    repositorio = RepositorioPostgres(DSN)
    repositorio.preparar()
    return repositorio


def _servicio(repositorio, almacen=None) -> ServicioDePolizas:
    return ServicioDePolizas(
        repositorio,
        RelojFijo(INSTANTE),
        almacen=almacen or AlmacenDeDocumentosEnMemoria(),
        generador=GeneradorPdf(),
        auditoria=Auditoria(),
    )


def test_la_poliza_emitida_se_lee_igual_desde_otra_replica(repositorio):
    almacen = AlmacenDeDocumentosEnMemoria()
    poliza, _ = _servicio(repositorio, almacen).emitir(SOLICITUD)

    otra = _servicio(RepositorioPostgres(DSN), almacen)

    assert otra.del_cliente(CLIENTE) == (poliza,)
    assert otra.poliza_del_cliente(poliza.numero, CLIENTE) == poliza
    assert otra.emitir(SOLICITUD) == (poliza, False)
    assert poliza.id_decision == f"DEC-{poliza.numero}"
    assert otra.documento(poliza.numero, CLIENTE)[1].startswith(b"%PDF")


def test_dos_replicas_que_emiten_a_la_vez_dejan_una_poliza(repositorio):
    almacen = AlmacenDeDocumentosEnMemoria()

    def emitir(_):
        return _servicio(RepositorioPostgres(DSN), almacen).emitir(SOLICITUD)[0]

    with ThreadPoolExecutor(6) as hilos:
        polizas = list(hilos.map(emitir, range(6)))

    assert len({p.numero for p in polizas}) == 1
    assert len(repositorio.del_cliente(CLIENTE)) == 1


def test_guardar_dos_veces_la_semilla_deja_lo_mismo(repositorio):
    semilla = Poliza("POL-STG-1", CLIENTE, "auto", date(2026, 1, 1), date(2026, 12, 31))
    repositorio.guardar(semilla)
    repositorio.guardar(replace(semilla, fin=date(2027, 1, 31)))

    assert repositorio.por_numero("POL-STG-1").fin == date(2027, 1, 31)
    assert repositorio.por_suscripcion("SUS-NO") is None
    assert repositorio.por_numero("POL-NO") is None


def test_salud_reporta_la_base_de_polizas(monkeypatch):
    monkeypatch.setenv(dependencias.VARIABLE_DSN, DSN)
    monkeypatch.delenv(dependencias.VARIABLE_SEMILLA, raising=False)
    dependencias.reiniciar()
    try:
        estados = {e.nombre: e.estado for e in dependencias.estado_dependencias()}
    finally:
        dependencias.reiniciar()

    assert estados["repositorio_polizas"] == "ok"
