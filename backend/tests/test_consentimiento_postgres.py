"""Pruebas de ARQ-05 contra PostgreSQL: el consentimiento persiste y se comparte entre réplicas.

Corren si hay una base: `CONSENTIMIENTO_DATABASE_URL` o, en su defecto, `AUDITORIA_DATABASE_URL`.
"""

from __future__ import annotations

import os

import psycopg
import pytest

from app.modulos.consentimiento.api import dependencias
from app.modulos.consentimiento.dominio.modelo import Estado, TipoEvento
from app.modulos.consentimiento.infraestructura.repositorio_postgres import RepositorioPostgres
from app.modulos.perfilamiento.dominio.modelo import Origen
from app.modulos.perfilamiento.infraestructura.proveedor_simulado import (
    Modo,
    ProveedorOpenFinanceSimulado,
)

from .conftest import INSTANTE_INICIAL
from .test_consentimiento_antes_de_cache import CLIENTE, adaptador, consentimientos, otorgar

DSN = os.environ.get("CONSENTIMIENTO_DATABASE_URL") or os.environ.get("AUDITORIA_DATABASE_URL")

pytestmark = pytest.mark.skipif(
    not DSN, reason="Define CONSENTIMIENTO_DATABASE_URL para probar contra PostgreSQL"
)


@pytest.fixture(autouse=True)
def limpio():
    with psycopg.connect(DSN, autocommit=True) as conexion:
        conexion.execute("DROP SCHEMA IF EXISTS consentimiento CASCADE")


def test_el_consentimiento_vuelve_igual_con_su_historico():
    servicio = consentimientos(RepositorioPostgres(DSN))
    id_consentimiento = otorgar(servicio)
    servicio.revocar(CLIENTE, id_consentimiento, "movil")

    leido = consentimientos(RepositorioPostgres(DSN)).obtener(CLIENTE, id_consentimiento)

    assert leido.estado(INSTANTE_INICIAL) is Estado.REVOCADO
    assert [e.tipo for e in leido.eventos] == [TipoEvento.OTORGADO, TipoEvento.REVOCADO]
    assert leido.entidades_origen == ("ent-andes",)
    assert leido == servicio.obtener(CLIENTE, id_consentimiento)


def test_dos_replicas_no_usan_el_perfil_de_un_cliente_que_revoco():
    """RC-04 (ARQ-05, criterio 3): revocar en la réplica B y cotizar en la A, sin Redis."""
    replica_a = consentimientos(RepositorioPostgres(DSN))
    replica_b = consentimientos(RepositorioPostgres(DSN))
    proveedor = ProveedorOpenFinanceSimulado()
    perfilamiento_a = adaptador(replica_a, proveedor)
    perfilamiento_b = adaptador(replica_b, proveedor)
    id_consentimiento = otorgar(replica_a)
    perfilamiento_a.perfil_de(CLIENTE, id_consentimiento)
    perfilamiento_b.perfil_de(CLIENTE, id_consentimiento)

    replica_b.revocar(CLIENTE, id_consentimiento, "movil")
    proveedor.modo = Modo.CAIDO

    assert perfilamiento_a.perfil_de(CLIENTE, id_consentimiento).origen is Origen.NINGUNO
    assert perfilamiento_b.perfil_de(CLIENTE, id_consentimiento).origen is Origen.NINGUNO
    assert not replica_a.consentimiento_vigente(CLIENTE, "cotizacion").vigente


def test_salud_reporta_la_base_de_consentimientos(monkeypatch):
    monkeypatch.setenv(dependencias.VARIABLE_DSN, DSN)
    dependencias.reiniciar()
    try:
        estados = {e.nombre: e.estado for e in dependencias.estado_dependencias()}
    finally:
        dependencias.reiniciar()

    assert estados["repositorio_consentimientos"] == "ok"
