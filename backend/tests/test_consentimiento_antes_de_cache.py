"""Pruebas de ARQ-05 (#215): el consentimiento se verifica antes de usar la caché de perfiles.

La caché es local a cada réplica; el evento de revocación solo la vacía en la réplica que lo
recibe. Con la verificación, ninguna réplica usa el perfil de un cliente que revocó en otra
(RC-04, criterio 3). Las pruebas contra PostgreSQL, con dos réplicas de verdad, están en
`test_consentimiento_postgres.py`.
"""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.compartido.eventos import BusEnProceso
from app.compartido.reloj import RelojFijo
from app.main import crear_app
from app.modulos.auditoria.aplicacion.servicio import ServicioDeAuditoria
from app.modulos.auditoria.infraestructura.almacen_memoria import AlmacenEnMemoria
from app.modulos.consentimiento.aplicacion.servicio import ServicioDeConsentimiento
from app.modulos.consentimiento.infraestructura.auditoria_nucleo import RegistroEnAuditoria
from app.modulos.consentimiento.infraestructura.repositorio_memoria import RepositorioEnMemoria
from app.modulos.perfilamiento.api import dependencias
from app.modulos.perfilamiento.dominio.modelo import Origen
from app.modulos.perfilamiento.infraestructura.adaptador_resiliente import (
    AdaptadorOpenFinanceResiliente,
)
from app.modulos.perfilamiento.infraestructura.cache_perfiles import CacheDePerfiles
from app.modulos.perfilamiento.infraestructura.circuit_breaker import CircuitBreaker
from app.modulos.perfilamiento.infraestructura.consentimiento_nucleo import ConsentimientoDelNucleo
from app.modulos.perfilamiento.infraestructura.proveedor_simulado import (
    Modo,
    ProveedorOpenFinanceSimulado,
)

from .conftest import INSTANTE_INICIAL

CLIENTE = "CLI-2026-000318"


def consentimientos(repositorio) -> ServicioDeConsentimiento:
    """Una réplica del módulo de consentimiento: su propio bus, el repositorio que le pasen."""
    return ServicioDeConsentimiento(
        repositorio=repositorio,
        auditoria=RegistroEnAuditoria(ServicioDeAuditoria(AlmacenEnMemoria())),
        bus=BusEnProceso(),
        reloj=RelojFijo(INSTANTE_INICIAL),
    )


def otorgar(servicio: ServicioDeConsentimiento) -> str:
    return servicio.otorgar(
        id_cliente=CLIENTE,
        finalidad="cotizacion",
        entidades_origen=["ent-andes"],
        version_texto="cotizacion-2026.1",
        canal="web",
    ).id_consentimiento


def adaptador(servicio: ServicioDeConsentimiento, proveedor) -> AdaptadorOpenFinanceResiliente:
    """Una réplica de perfilamiento: su propia caché local."""
    return AdaptadorOpenFinanceResiliente(
        proveedor,
        CacheDePerfiles(),
        breaker=CircuitBreaker(),
        sondeo_en_segundo_plano=False,
        verificacion=ConsentimientoDelNucleo(servicio),
    )


@pytest.fixture
def repositorio() -> RepositorioEnMemoria:
    return RepositorioEnMemoria()


def test_revocar_en_una_replica_impide_usar_la_cache_en_la_otra(repositorio):
    replica_a, replica_b = consentimientos(repositorio), consentimientos(repositorio)
    proveedor = ProveedorOpenFinanceSimulado()
    perfilamiento_a = adaptador(replica_a, proveedor)
    id_consentimiento = otorgar(replica_a)
    assert perfilamiento_a.perfil_de(CLIENTE, id_consentimiento).origen is Origen.PROVEEDOR

    replica_b.revocar(CLIENTE, id_consentimiento, "movil")
    proveedor.modo = Modo.CAIDO
    respuesta = perfilamiento_a.perfil_de(CLIENTE, id_consentimiento)

    assert respuesta.origen is Origen.NINGUNO
    assert perfilamiento_a.cache.obtener(CLIENTE) is None


def test_con_el_consentimiento_vigente_se_sigue_usando_la_cache(repositorio):
    servicio = consentimientos(repositorio)
    proveedor = ProveedorOpenFinanceSimulado()
    perfilamiento = adaptador(servicio, proveedor)
    id_consentimiento = otorgar(servicio)
    perfilamiento.perfil_de(CLIENTE, id_consentimiento)

    proveedor.modo = Modo.CAIDO
    respuesta = perfilamiento.perfil_de(CLIENTE, id_consentimiento)

    assert respuesta.origen is Origen.CACHE
    resumen = perfilamiento.resumen()["verificacion_consentimiento"]
    assert resumen["activa"] is True
    assert resumen["llamadas"] == 1


def test_si_la_verificacion_falla_no_se_usa_la_cache():
    """Ante la duda, tarifa estándar: la base caída no puede filtrar un perfil revocado."""

    class Caida:
        def vigente(self, id_cliente, id_consentimiento):
            raise ConnectionError("Cloud SQL no responde")

    proveedor = ProveedorOpenFinanceSimulado()
    perfilamiento = AdaptadorOpenFinanceResiliente(
        proveedor, CacheDePerfiles(), sondeo_en_segundo_plano=False, verificacion=Caida()
    )
    perfilamiento.perfil_de(CLIENTE, "CONSENT-1")
    proveedor.modo = Modo.CAIDO

    assert perfilamiento.perfil_de(CLIENTE, "CONSENT-1").origen is Origen.NINGUNO
    assert perfilamiento.resumen()["verificacion_consentimiento"]["tasa_error"] == 1.0


def test_con_el_proveedor_sano_no_se_paga_la_verificacion(repositorio):
    """La consulta adicional solo ocurre en el camino de la caché, no en el normal."""
    servicio = consentimientos(repositorio)
    perfilamiento = adaptador(servicio, ProveedorOpenFinanceSimulado())
    id_consentimiento = otorgar(servicio)

    perfilamiento.perfil_de(CLIENTE, id_consentimiento)

    assert perfilamiento.resumen()["verificacion_consentimiento"]["llamadas"] == 0


def test_esta_vigente_no_responde_por_un_consentimiento_ajeno(repositorio):
    servicio = consentimientos(repositorio)
    id_consentimiento = otorgar(servicio)

    assert servicio.esta_vigente(CLIENTE, id_consentimiento)
    assert not servicio.esta_vigente("CLI-OTRO", id_consentimiento)
    assert not servicio.esta_vigente(CLIENTE, "CONSENT-NO-EXISTE")


# -- umbrales del circuito como configuración ---------------------------------------------


def test_sin_variables_el_circuito_usa_los_umbrales_de_d02():
    resumen = dependencias.crear_breaker({}).resumen()

    assert (resumen["umbral_fallos"], resumen["ventana_s"], resumen["minimo_llamadas"]) == (
        0.10,
        30.0,
        5,
    )


def test_los_umbrales_se_leen_del_entorno():
    breaker = dependencias.crear_breaker(
        {
            "PERFILAMIENTO_CIRCUITO_UMBRAL": "0.5",
            "PERFILAMIENTO_CIRCUITO_VENTANA_S": "10",
            "PERFILAMIENTO_CIRCUITO_MINIMO_LLAMADAS": "2",
        }
    )

    for _ in range(2):
        breaker.registrar(False)

    assert not breaker.permite()
    assert breaker.resumen()["umbral_fallos"] == 0.5


@pytest.mark.parametrize(
    "entorno",
    [
        {"PERFILAMIENTO_CIRCUITO_UMBRAL": "diez"},
        {"PERFILAMIENTO_CIRCUITO_UMBRAL": "0"},
        {"PERFILAMIENTO_CIRCUITO_UMBRAL": "1.5"},
        {"PERFILAMIENTO_CIRCUITO_VENTANA_S": "0"},
        {"PERFILAMIENTO_CIRCUITO_MINIMO_LLAMADAS": "0"},
    ],
)
def test_un_umbral_invalido_no_arranca(entorno):
    with pytest.raises(RuntimeError):
        dependencias.crear_breaker(entorno)


def test_salud_muestra_los_umbrales_y_la_verificacion(monkeypatch):
    monkeypatch.setenv("PERFILAMIENTO_CIRCUITO_UMBRAL", "0.2")
    dependencias.reiniciar()
    try:
        resumen = dependencias.obtener_adaptador().resumen()
        salud = TestClient(crear_app()).get("/salud")
    finally:
        dependencias.reiniciar()

    assert resumen["circuito"]["umbral_fallos"] == 0.2
    assert resumen["verificacion_consentimiento"]["activa"] is True
    assert salud.status_code == 200
