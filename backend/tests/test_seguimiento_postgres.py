"""Pruebas de WEB-F06 contra PostgreSQL: el estado nuevo se ve desde otra réplica.

Corren si hay una base: `SINIESTROS_DATABASE_URL` o, en su defecto, `AUDITORIA_DATABASE_URL`.
"""

from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor

import psycopg
import pytest

from app.modulos.siniestros.dominio.modelo import EstadoSiniestro
from app.modulos.siniestros.dominio.seguimiento import (
    CambioConcurrenteError,
    TransicionNoPermitidaError,
)
from app.modulos.siniestros.infraestructura.repositorio_postgres import RepositorioPostgres

from .test_siniestros_postgres import CLIENTE, DATOS, DSN, _servicio

pytestmark = pytest.mark.skipif(
    not DSN, reason="Define SINIESTROS_DATABASE_URL para probar contra PostgreSQL"
)

E = EstadoSiniestro
FOTO = b"\xff\xd8\xff\xe0" + b"foto" * 100


@pytest.fixture
def repositorio() -> RepositorioPostgres:
    with psycopg.connect(DSN, autocommit=True) as conexion:
        conexion.execute("DROP SCHEMA IF EXISTS siniestros CASCADE")
    return RepositorioPostgres(DSN)


def test_el_cambio_se_ve_desde_otra_replica_con_historial_y_requerimientos(repositorio):
    """Criterio 2: el cliente refresca cualquier canal y ve el estado nuevo."""
    servicio = _servicio(repositorio)
    siniestro, _ = servicio.reportar(CLIENTE, DATOS, "web", "clave-seg-1")
    servicio.cambiar_estado(
        siniestro.id_siniestro, E.REQUIERE_INFORMACION, "Falta soporte", ["Factura"]
    )

    otra = _servicio(RepositorioPostgres(DSN))
    visto = otra.obtener(CLIENTE, siniestro.id_siniestro)
    historial = otra.historial(CLIENTE, siniestro.id_siniestro)
    [pedido] = otra.requerimientos(CLIENTE, siniestro.id_siniestro)

    assert visto.estado is E.REQUIERE_INFORMACION
    assert visto.estado_actualizado_en is not None
    assert [e.estado_nuevo for e in historial] == [E.REPORTADO, E.REQUIERE_INFORMACION]
    assert historial[1].motivo == "Falta soporte" and historial[1].id_decision
    assert pedido.descripcion == "Factura" and pedido.pendiente

    evidencia = otra.adjuntar_evidencia(
        CLIENTE,
        siniestro.id_siniestro,
        "image/jpeg",
        FOTO,
        "factura.jpg",
        "movil",
        id_requerimiento=pedido.id_requerimiento,
    )
    [atendido] = servicio.requerimientos(CLIENTE, siniestro.id_siniestro)
    assert not atendido.pendiente and atendido.id_evidencia == evidencia.id_evidencia
    assert not repositorio.atender_requerimiento(
        pedido.id_requerimiento, "EVI-X", visto.reportado_en
    )


def test_dos_replicas_que_cambian_a_la_vez_dejan_un_cambio(repositorio):
    siniestro, _ = _servicio(repositorio).reportar(CLIENTE, DATOS, "web", "clave-seg-2")

    def cambiar(_):
        try:
            return _servicio(RepositorioPostgres(DSN)).cambiar_estado(
                siniestro.id_siniestro, E.EN_EVALUACION
            )
        except (CambioConcurrenteError, TransicionNoPermitidaError):
            return None

    with ThreadPoolExecutor(6) as hilos:
        resultados = list(hilos.map(cambiar, range(6)))

    assert len([r for r in resultados if r is not None]) == 1
    assert len(repositorio.eventos(siniestro.id_siniestro)) == 1


def test_una_base_del_sprint_2_se_actualiza_sola(repositorio):
    """La columna nueva se agrega sobre una tabla que ya existía sin ella."""
    with psycopg.connect(DSN, autocommit=True) as conexion:
        conexion.execute("CREATE SCHEMA siniestros")
        conexion.execute(
            "CREATE TABLE siniestros.siniestros (id_siniestro TEXT PRIMARY KEY, referencia TEXT "
            "NOT NULL UNIQUE, id_cliente TEXT NOT NULL, numero_poliza TEXT NOT NULL, tipo_evento "
            "TEXT NOT NULL, ocurrido_el DATE NOT NULL, descripcion TEXT NOT NULL, monto_estimado "
            "BIGINT, canal TEXT NOT NULL, estado TEXT NOT NULL, reportado_en TIMESTAMPTZ NOT NULL,"
            " clave_idempotencia TEXT, CONSTRAINT siniestros_clave_por_cliente UNIQUE "
            "(id_cliente, clave_idempotencia))"
        )

    repositorio.preparar()
    siniestro, _ = _servicio(repositorio).reportar(CLIENTE, DATOS, "web", "clave-seg-3")

    assert repositorio.por_id(siniestro.id_siniestro).estado_actualizado_en is None
    assert repositorio.por_id("SIN-NO") is None
