"""Pruebas de WEB-F05 contra PostgreSQL: persistencia e idempotencia en la base.

Corren si hay una base: `SINIESTROS_DATABASE_URL` o, en su defecto, `AUDITORIA_DATABASE_URL`
(en el CI, el servicio `postgres` del job `backend`). Los siniestros viven en su propio
esquema y comparten la base sin pisar a los demás módulos.
"""

from __future__ import annotations

import os
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
from datetime import UTC, datetime, timedelta

import psycopg
import pytest

from app.compartido.eventos import BusEnProceso
from app.compartido.reloj import RelojFijo
from app.modulos.auditoria.aplicacion.servicio import ServicioDeAuditoria
from app.modulos.auditoria.infraestructura.almacen_memoria import AlmacenEnMemoria
from app.modulos.siniestros.api import dependencias
from app.modulos.siniestros.aplicacion.servicio import ServicioDeSiniestros
from app.modulos.siniestros.dominio.evidencias import Captura
from app.modulos.siniestros.dominio.modelo import DatosDeReporte, EstadoSiniestro, TipoEvento
from app.modulos.siniestros.dominio.puertos import VigenciaDePoliza
from app.modulos.siniestros.infraestructura.almacen_evidencias_memoria import (
    AlmacenDeEvidenciasEnMemoria,
)
from app.modulos.siniestros.infraestructura.auditoria_nucleo import RegistroEnAuditoria
from app.modulos.siniestros.infraestructura.repositorio_postgres import RepositorioPostgres

DSN = os.environ.get("SINIESTROS_DATABASE_URL") or os.environ.get("AUDITORIA_DATABASE_URL")

pytestmark = pytest.mark.skipif(
    not DSN, reason="Define SINIESTROS_DATABASE_URL para probar contra PostgreSQL"
)

INSTANTE = datetime(2026, 10, 26, 15, 0, tzinfo=UTC)
CLIENTE = "CLI-2026-000318"
POLIZA = VigenciaDePoliza(
    "POL-1", "auto", INSTANTE.date() - timedelta(days=30), INSTANTE.date() + timedelta(days=30)
)
DATOS = DatosDeReporte(
    numero_poliza="POL-1",
    tipo_evento=TipoEvento.HURTO,
    ocurrido_el=INSTANTE.date() - timedelta(days=1),
    descripcion="Me robaron el celular en el bus.",
)


class Polizas:
    def poliza_del_cliente(self, numero, id_cliente):
        return POLIZA if numero == "POL-1" else None


@pytest.fixture
def repositorio() -> RepositorioPostgres:
    with psycopg.connect(DSN, autocommit=True) as conexion:
        conexion.execute("DROP SCHEMA IF EXISTS siniestros CASCADE")
    return RepositorioPostgres(DSN)


def _servicio(repositorio, reloj: RelojFijo | None = None) -> ServicioDeSiniestros:
    return ServicioDeSiniestros(
        repositorio=repositorio,
        polizas=Polizas(),
        auditoria=RegistroEnAuditoria(ServicioDeAuditoria(AlmacenEnMemoria())),
        bus=BusEnProceso(),
        reloj=reloj or RelojFijo(INSTANTE),
        almacen=AlmacenDeEvidenciasEnMemoria(),
    )


def test_el_esquema_es_idempotente(repositorio):
    repositorio.preparar()
    repositorio.preparar()
    repositorio.verificar_conexion()


def test_el_siniestro_vuelve_igual_a_como_se_guardo(repositorio):
    siniestro, _ = _servicio(repositorio).reportar(
        CLIENTE, replace(DATOS, monto_estimado=900_000), "movil", "clave-pg-1"
    )

    leido = repositorio.obtener(CLIENTE, siniestro.id_siniestro)

    assert leido == siniestro
    assert leido.estado is EstadoSiniestro.REPORTADO
    assert repositorio.obtener("CLI-OTRO", siniestro.id_siniestro) is None


def test_la_referencia_es_consecutiva(repositorio):
    assert repositorio.siguiente_referencia(2026) == "SIN-2026-000001"
    assert repositorio.siguiente_referencia(2026) == "SIN-2026-000002"


def test_la_clave_se_sostiene_tras_un_reinicio(repositorio):
    """Criterio 4 con la base: otro proceso con la misma clave obtiene la misma referencia."""
    primero, _ = _servicio(repositorio).reportar(CLIENTE, DATOS, "web", "clave-pg-2")

    segundo, nuevo = _servicio(RepositorioPostgres(DSN)).reportar(
        CLIENTE, DATOS, "movil", "clave-pg-2"
    )

    assert nuevo is False
    assert segundo.referencia == primero.referencia
    assert len(repositorio.del_cliente(CLIENTE)) == 1


def test_un_doble_envio_simultaneo_deja_uno_solo_en_la_base(repositorio):
    repositorio.preparar()

    def reportar(_):
        servicio = _servicio(RepositorioPostgres(DSN))
        return servicio.reportar(CLIENTE, DATOS, "web", "clave-pg-doble")[0].referencia

    with ThreadPoolExecutor(max_workers=6) as hilos:
        referencias = set(hilos.map(reportar, range(12)))

    assert len(referencias) == 1
    assert len(repositorio.del_cliente(CLIENTE)) == 1


def test_sin_clave_no_hay_choque(repositorio):
    servicio = _servicio(repositorio)
    servicio.reportar(CLIENTE, DATOS, "web")
    servicio.reportar(CLIENTE, DATOS, "web")

    assert len(repositorio.del_cliente(CLIENTE)) == 2


def test_la_lista_va_del_mas_reciente_al_mas_antiguo(repositorio):
    reloj = RelojFijo(INSTANTE)
    servicio = _servicio(repositorio, reloj)
    primero, _ = servicio.reportar(CLIENTE, DATOS, "web", "clave-pg-a")
    reloj.avanzar(60)
    segundo, _ = servicio.reportar(CLIENTE, DATOS, "web", "clave-pg-b")

    assert [s.referencia for s in repositorio.del_cliente(CLIENTE)] == [
        segundo.referencia,
        primero.referencia,
    ]


def test_la_composicion_usa_postgres_con_la_variable(monkeypatch):
    monkeypatch.setenv(dependencias.VARIABLE_DSN, DSN)
    dependencias.reiniciar()
    try:
        estado = {d.nombre: d for d in dependencias.estado_dependencias()}
    finally:
        dependencias.reiniciar()

    assert estado["repositorio_siniestros"].estado == "ok"


def test_la_evidencia_conserva_su_captura_en_la_base(repositorio):
    servicio = _servicio(repositorio)
    siniestro, _ = servicio.reportar(CLIENTE, DATOS, "movil", "clave-pg-evid")
    captura = Captura(
        tomada_en=INSTANTE - timedelta(minutes=1), latitud=4.65, longitud=-74.08, lugar="Bogotá"
    )

    evidencia = servicio.adjuntar_evidencia(
        CLIENTE,
        siniestro.id_siniestro,
        "image/jpeg",
        b"\xff\xd8\xff\xe0foto",
        "choque.jpg",
        "movil",
        captura,
    )

    assert RepositorioPostgres(DSN).evidencias(siniestro.id_siniestro) == (evidencia,)
