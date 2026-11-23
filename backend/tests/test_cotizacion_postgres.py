"""Pruebas de WEB-F01 contra PostgreSQL: persistencia, consecutivo e idempotencia.

Corren si hay una base disponible: `COTIZACION_DATABASE_URL` o, en su defecto,
`AUDITORIA_DATABASE_URL` (en el CI, el servicio `postgres` del job `backend`). La cotización
vive en su propio esquema, así que comparte la base sin pisar la auditoría. Cada prueba
parte de un esquema limpio.
"""

from __future__ import annotations

import os

import psycopg
import pytest

from app.modulos.cotizacion.aplicacion.servicio import (
    ServicioDeCotizacion,
    SolicitudDeCotizacion,
)
from app.modulos.cotizacion.dominio.modelo import (
    VIGENCIA_OFERTA,
    BasePerfilamiento,
    EstadoCotizacion,
    MotivoTarifa,
    ProductoId,
)
from app.modulos.cotizacion.infraestructura.adaptadores_simulados import (
    PerfilamientoSimulado,
    RegistroDeDecisionesEnMemoria,
)
from app.modulos.cotizacion.infraestructura.repositorio_postgres import RepositorioPostgres

from .conftest import CLIENTE, CONSENTIMIENTO

DSN = os.environ.get("COTIZACION_DATABASE_URL") or os.environ.get("AUDITORIA_DATABASE_URL")

pytestmark = pytest.mark.skipif(
    not DSN, reason="Define COTIZACION_DATABASE_URL para probar contra PostgreSQL"
)


@pytest.fixture
def repositorio() -> RepositorioPostgres:
    with psycopg.connect(DSN, autocommit=True) as conexion:
        conexion.execute("DROP SCHEMA IF EXISTS cotizacion CASCADE")
    return RepositorioPostgres(DSN)


def _servicio(repositorio, reloj, consentimiento, perfilamiento=None) -> ServicioDeCotizacion:
    return ServicioDeCotizacion(
        repositorio=repositorio,
        consentimiento=consentimiento,
        perfilamiento=perfilamiento or PerfilamientoSimulado(),
        auditoria=RegistroDeDecisionesEnMemoria(),
        reloj=reloj,
    )


def _solicitud(asegurado, **opciones) -> SolicitudDeCotizacion:
    return SolicitudDeCotizacion(
        producto=ProductoId.AUTO, asegurado=asegurado, id_cliente=CLIENTE, **opciones
    )


def test_el_esquema_es_idempotente(repositorio):
    repositorio.preparar()
    repositorio.preparar()
    repositorio.verificar_conexion()


def test_el_consecutivo_continua_la_numeracion_del_prototipo(repositorio):
    assert repositorio.siguiente_id(2026) == "QT-2026-08831"
    assert repositorio.siguiente_id(2026) == "QT-2026-08832"


def test_la_cotizacion_vuelve_igual_a_como_se_guardo(repositorio, reloj, consentimiento, asegurado):
    consentimiento.otorgar(CLIENTE, CONSENTIMIENTO)
    servicio = _servicio(repositorio, reloj, consentimiento, PerfilamientoSimulado(degradado=True))

    original = servicio.cotizar(_solicitud(asegurado, canal="web"))

    leida = repositorio.obtener(original.id_cotizacion)
    assert leida == original
    assert leida.oferta.base_perfilamiento is BasePerfilamiento.OPEN_FINANCE_EN_CACHE
    assert leida.perfil is not None and leida.perfil.desde_cache is True


def test_una_cotizacion_estandar_conserva_su_motivo(repositorio, reloj, consentimiento, asegurado):
    servicio = _servicio(repositorio, reloj, consentimiento)

    original = servicio.cotizar(_solicitud(asegurado, usar_open_finance=False))

    leida = repositorio.obtener(original.id_cotizacion)
    assert leida.motivo_tarifa is MotivoTarifa.OPEN_FINANCE_DESACTIVADO
    assert leida.usar_open_finance is False
    assert leida.perfil is None


def test_una_cotizacion_inexistente_es_none(repositorio):
    assert repositorio.obtener("QT-2026-99999") is None


def test_la_cotizacion_sobrevive_a_un_reinicio_del_nucleo(
    repositorio, reloj, consentimiento, asegurado
):
    """Criterio 5: con un repositorio nuevo (otro proceso), sigue consultable con su estado."""
    consentimiento.otorgar(CLIENTE, CONSENTIMIENTO)
    original = _servicio(repositorio, reloj, consentimiento).cotizar(_solicitud(asegurado))

    tras_reinicio = _servicio(RepositorioPostgres(DSN), reloj, consentimiento)
    leida = tras_reinicio.obtener(original.id_cotizacion)

    assert leida.oferta.prima_mensual == original.oferta.prima_mensual
    assert leida.estado(reloj.ahora()) is EstadoCotizacion.VIGENTE
    reloj.avanzar(VIGENCIA_OFERTA.total_seconds())
    assert tras_reinicio.obtener(original.id_cotizacion).estado(reloj.ahora()) is (
        EstadoCotizacion.VENCIDA
    )


def test_la_idempotencia_se_sostiene_tras_un_reinicio(
    repositorio, reloj, consentimiento, asegurado
):
    primera = _servicio(repositorio, reloj, consentimiento).cotizar(_solicitud(asegurado))

    otra_instancia = _servicio(RepositorioPostgres(DSN), reloj, consentimiento)
    repetida = otra_instancia.cotizar(_solicitud(asegurado))

    assert repetida.id_cotizacion == primera.id_cotizacion


def test_vencida_la_oferta_la_huella_ya_no_la_encuentra(
    repositorio, reloj, consentimiento, asegurado
):
    servicio = _servicio(repositorio, reloj, consentimiento)
    primera = servicio.cotizar(_solicitud(asegurado))
    reloj.avanzar(VIGENCIA_OFERTA.total_seconds())

    assert repositorio.vigente_por_huella(CLIENTE, primera.huella, reloj.ahora()) is None
    assert servicio.cotizar(_solicitud(asegurado)).id_cotizacion != primera.id_cotizacion


def test_recotizar_persiste_la_nueva_con_su_origen(repositorio, reloj, consentimiento, asegurado):
    servicio = _servicio(repositorio, reloj, consentimiento)
    original = servicio.cotizar(_solicitud(asegurado))
    reloj.avanzar(VIGENCIA_OFERTA.total_seconds())

    nueva = servicio.recotizar(original.id_cotizacion)

    assert repositorio.obtener(nueva.id_cotizacion).id_cotizacion_origen == original.id_cotizacion
