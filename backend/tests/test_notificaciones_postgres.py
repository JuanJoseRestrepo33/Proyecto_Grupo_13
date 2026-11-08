"""Pruebas de MOB-F06 contra PostgreSQL: un push por evento aunque haya varias réplicas.

Corren si hay una base: `NOTIFICACIONES_DATABASE_URL` o, en su defecto, `AUDITORIA_DATABASE_URL`.
"""

from __future__ import annotations

import os
from concurrent.futures import ThreadPoolExecutor

import psycopg
import pytest

from app.compartido.reloj import RelojFijo
from app.modulos.notificaciones.api import dependencias
from app.modulos.notificaciones.aplicacion.servicio import ServicioDeNotificaciones
from app.modulos.notificaciones.dominio.modelo import EstadoEntrega
from app.modulos.notificaciones.infraestructura.proveedor_simulado import ProveedorPushSimulado
from app.modulos.notificaciones.infraestructura.repositorio_postgres import (
    DispositivosPostgres,
    NotificacionesPostgres,
)

from .test_notificaciones import CLIENTE, INSTANTE, TOKEN, _evento

DSN = os.environ.get("NOTIFICACIONES_DATABASE_URL") or os.environ.get("AUDITORIA_DATABASE_URL")

pytestmark = pytest.mark.skipif(
    not DSN, reason="Define NOTIFICACIONES_DATABASE_URL para probar contra PostgreSQL"
)


@pytest.fixture(autouse=True)
def limpio():
    with psycopg.connect(DSN, autocommit=True) as conexion:
        conexion.execute("DROP SCHEMA IF EXISTS notificaciones CASCADE")


def _servicio(proveedor) -> ServicioDeNotificaciones:
    return ServicioDeNotificaciones(
        DispositivosPostgres(DSN), NotificacionesPostgres(DSN), proveedor, RelojFijo(INSTANTE)
    )


def test_registro_y_notificacion_se_leen_desde_otra_replica():
    proveedor = ProveedorPushSimulado()
    servicio = _servicio(proveedor)
    _, nuevo = servicio.registrar_dispositivo(CLIENTE, TOKEN)
    _, nuevo_otra_vez = _servicio(proveedor).registrar_dispositivo(CLIENTE, TOKEN)

    servicio.al_cambiar_estado(_evento())
    otra = _servicio(proveedor)
    [notificacion] = otra.notificaciones(CLIENTE)
    otra.marcar_leida(CLIENTE, notificacion.id_notificacion)

    assert nuevo and not nuevo_otra_vez
    assert notificacion.estado is EstadoEntrega.ENVIADA and notificacion.dispositivos == 1
    assert notificacion.datos["id_siniestro"] == "SIN-1"
    assert servicio.notificaciones(CLIENTE)[0].leida
    assert not otra._notificaciones.marcar_leida("CLI-OTRO", notificacion.id_notificacion, INSTANTE)
    [dispositivo] = otra.dispositivos(CLIENTE)
    otra.dar_de_baja(CLIENTE, dispositivo.id_dispositivo)
    assert servicio.dispositivos(CLIENTE) == ()


def test_dos_replicas_con_el_mismo_evento_mandan_un_solo_push():
    proveedor = ProveedorPushSimulado()
    _servicio(proveedor).registrar_dispositivo(CLIENTE, TOKEN)

    with ThreadPoolExecutor(6) as hilos:
        list(hilos.map(lambda _: _servicio(proveedor).al_cambiar_estado(_evento()), range(6)))

    assert len(proveedor.enviados) == 1
    assert len(_servicio(proveedor).notificaciones(CLIENTE)) == 1


def test_salud_reporta_la_base_de_notificaciones(monkeypatch):
    monkeypatch.setenv(dependencias.VARIABLE_DSN, DSN)
    dependencias.reiniciar()
    try:
        estados = {e.nombre: e.estado for e in dependencias.estado_dependencias()}
    finally:
        dependencias.reiniciar()

    assert estados["repositorio_notificaciones"] == "ok"
