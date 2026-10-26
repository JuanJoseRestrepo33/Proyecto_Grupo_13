"""Pruebas de ARQ-02: adaptador de Open Finance con presupuesto, circuit breaker y caché.

Cubren las tres correcciones de EXP-01 (D-01 presupuesto efectivo, D-02 mínimo de
llamadas, D-03 sondeo en segundo plano), el error tipado del dominio y la integración con
cotización y con la revocación del consentimiento.
"""

from __future__ import annotations

import itertools
import time

import pytest
from fastapi.testclient import TestClient

from app.main import crear_app
from app.modulos.auditoria.api import dependencias as auditoria_dependencias
from app.modulos.consentimiento.api import dependencias as consentimiento_dependencias
from app.modulos.cotizacion.api import dependencias as cotizacion_dependencias
from app.modulos.perfilamiento.api import dependencias
from app.modulos.perfilamiento.aplicacion.servicio import ServicioDePerfilamiento
from app.modulos.perfilamiento.dominio.errores import (
    PerfilSinConsentimientoError,
    ProveedorOpenFinanceNoDisponibleError,
)
from app.modulos.perfilamiento.dominio.modelo import Origen
from app.modulos.perfilamiento.infraestructura.adaptador_resiliente import (
    AdaptadorOpenFinanceResiliente,
)
from app.modulos.perfilamiento.infraestructura.cache_perfiles import CacheDePerfiles
from app.modulos.perfilamiento.infraestructura.circuit_breaker import CircuitBreaker, EstadoCircuito
from app.modulos.perfilamiento.infraestructura.proveedor_simulado import (
    PERFIL_POR_DEFECTO,
    Modo,
    ProveedorOpenFinanceSimulado,
)

CLIENTE = "CLI-0001"
CONSENTIMIENTO = "CONSENT-1"
PRESUPUESTO = 0.05
LATENCIA_DEGRADADO = 0.30


class RelojFalso:
    def __init__(self) -> None:
        self.t = 0.0

    def __call__(self) -> float:
        return self.t

    def avanzar(self, s: float) -> None:
        self.t += s


@pytest.fixture
def reloj() -> RelojFalso:
    return RelojFalso()


@pytest.fixture
def breaker(reloj) -> CircuitBreaker:
    return CircuitBreaker(sondear_tras_s=10, reloj_monotono=reloj)


@pytest.fixture
def proveedor() -> ProveedorOpenFinanceSimulado:
    return ProveedorOpenFinanceSimulado(latencia_degradado_s=LATENCIA_DEGRADADO)


@pytest.fixture
def cache() -> CacheDePerfiles:
    return CacheDePerfiles()


@pytest.fixture
def adaptador(proveedor, cache, breaker) -> AdaptadorOpenFinanceResiliente:
    return AdaptadorOpenFinanceResiliente(
        proveedor,
        cache,
        breaker=breaker,
        presupuesto_s=PRESUPUESTO,
        tiempo_limite_sondeo_s=0.5,
        sondeo_en_segundo_plano=False,
    )


def abrir_circuito(adaptador, proveedor) -> None:
    proveedor.modo = Modo.CAIDO
    for _ in range(5):
        adaptador.perfil_de(CLIENTE, CONSENTIMIENTO)
    assert adaptador.breaker.estado is EstadoCircuito.ABIERTO


# --- Circuit breaker: D-02 y el ciclo abierto → sondeo → cerrado -----------------------------


def test_el_circuito_no_se_evalua_con_menos_de_cinco_llamadas(breaker):
    for _ in range(4):
        breaker.registrar(False)
    assert breaker.estado is EstadoCircuito.CERRADO

    breaker.registrar(False)
    assert breaker.estado is EstadoCircuito.ABIERTO
    assert breaker.aperturas == 1


def test_el_circuito_se_abre_con_diez_por_ciento_de_fallos_en_la_ventana(breaker):
    for _ in range(9):
        breaker.registrar(True)
    breaker.registrar(False)
    assert breaker.estado is EstadoCircuito.ABIERTO


def test_los_fallos_fuera_de_la_ventana_no_cuentan(breaker, reloj):
    for _ in range(4):
        breaker.registrar(False)
    reloj.avanzar(31)
    breaker.registrar(False)
    assert breaker.estado is EstadoCircuito.CERRADO


def test_el_sondeo_se_autoriza_una_vez_pasado_el_tiempo_y_cierra_si_responde(breaker, reloj):
    for _ in range(5):
        breaker.registrar(False)
    assert breaker.debe_sondear() is False  # recién abierto
    reloj.avanzar(10)
    assert breaker.debe_sondear() is True
    assert breaker.debe_sondear() is False  # ya hay uno en curso

    breaker.resultado_sondeo(False)
    assert breaker.estado is EstadoCircuito.ABIERTO
    reloj.avanzar(10)
    assert breaker.debe_sondear() is True
    breaker.resultado_sondeo(True)
    assert breaker.estado is EstadoCircuito.CERRADO
    assert breaker.cierres == 1


# --- Adaptador: presupuesto (D-01), caché y error tipado -----------------------------------


def test_con_el_proveedor_sano_el_perfil_viene_del_proveedor_y_queda_en_cache(adaptador, cache):
    respuesta = adaptador.perfil_de(CLIENTE, CONSENTIMIENTO)

    assert respuesta.origen is Origen.PROVEEDOR
    assert respuesta.degradado is False
    assert respuesta.perfil == PERFIL_POR_DEFECTO
    assert cache.obtener(CLIENTE) == PERFIL_POR_DEFECTO


def test_el_proveedor_degradado_se_abandona_al_vencer_el_presupuesto(adaptador, proveedor):
    adaptador.perfil_de(CLIENTE, CONSENTIMIENTO)  # llena la caché
    proveedor.modo = Modo.DEGRADADO

    inicio = time.perf_counter()
    respuesta = adaptador.perfil_de(CLIENTE, CONSENTIMIENTO)
    transcurrido = time.perf_counter() - inicio

    assert respuesta.origen is Origen.CACHE
    assert respuesta.degradado is True
    assert respuesta.perfil == PERFIL_POR_DEFECTO
    assert transcurrido < LATENCIA_DEGRADADO  # no esperó al proveedor
    assert adaptador.metricas.tasa_error() == 0.5


def test_sin_cache_y_sin_proveedor_la_respuesta_es_ninguno(adaptador, proveedor):
    proveedor.modo = Modo.CAIDO

    respuesta = adaptador.perfil_de("CLI-nuevo", CONSENTIMIENTO)

    assert respuesta.perfil is None
    assert respuesta.origen is Origen.NINGUNO
    assert respuesta.degradado is True


def test_una_excepcion_cualquiera_del_proveedor_se_traduce_al_dominio(cache, breaker):
    class ProveedorRoto:
        def perfil_de(self, id_cliente, id_consentimiento):
            raise ConnectionResetError("socket cerrado")

        def sondear(self):
            raise ConnectionResetError("socket cerrado")

    adaptador = AdaptadorOpenFinanceResiliente(ProveedorRoto(), cache, breaker=breaker)

    with pytest.raises(ProveedorOpenFinanceNoDisponibleError) as error:
        adaptador._con_tiempo_limite(lambda: adaptador.proveedor.perfil_de("x", "y"), 0.5)
    assert error.value.detalles["causa"] == "ConnectionResetError"
    assert adaptador.perfil_de(CLIENTE, CONSENTIMIENTO).origen is Origen.NINGUNO


# --- Circuito abierto: no se llama al proveedor, se sondea y se recupera (D-03) -------------


def test_con_el_circuito_abierto_no_se_llama_al_proveedor(adaptador, proveedor):
    adaptador.perfil_de(CLIENTE, CONSENTIMIENTO)
    abrir_circuito(adaptador, proveedor)
    llamadas = proveedor.llamadas

    respuesta = adaptador.perfil_de(CLIENTE, CONSENTIMIENTO)

    assert respuesta.origen is Origen.CACHE
    assert proveedor.llamadas == llamadas


def test_el_sondeo_cierra_el_circuito_cuando_el_proveedor_vuelve(adaptador, proveedor, reloj):
    adaptador.perfil_de(CLIENTE, CONSENTIMIENTO)
    abrir_circuito(adaptador, proveedor)

    reloj.avanzar(10)
    adaptador.perfil_de(CLIENTE, CONSENTIMIENTO)  # sondeo: sigue caído
    assert proveedor.sondeos == 1
    assert adaptador.breaker.estado is EstadoCircuito.ABIERTO

    proveedor.modo = Modo.SANO
    reloj.avanzar(10)
    assert adaptador.perfil_de(CLIENTE, CONSENTIMIENTO).origen is Origen.CACHE  # sondea y cierra
    assert proveedor.sondeos == 2
    assert adaptador.breaker.estado is EstadoCircuito.CERRADO
    assert adaptador.perfil_de(CLIENTE, CONSENTIMIENTO).origen is Origen.PROVEEDOR


def test_el_sondeo_corre_en_segundo_plano_sin_bloquear_al_usuario(proveedor, cache, reloj):
    adaptador = AdaptadorOpenFinanceResiliente(
        proveedor,
        cache,
        breaker=CircuitBreaker(sondear_tras_s=0, reloj_monotono=reloj),
        presupuesto_s=PRESUPUESTO,
        tiempo_limite_sondeo_s=0.5,
        sondeo_en_segundo_plano=True,
    )
    abrir_circuito(adaptador, proveedor)
    proveedor.modo = Modo.DEGRADADO  # el sondeo tarda 300 ms; el usuario no debe esperarlos

    inicio = time.perf_counter()
    respuesta = adaptador.perfil_de(CLIENTE, CONSENTIMIENTO)
    transcurrido = time.perf_counter() - inicio
    adaptador.esperar_sondeo()

    assert respuesta.origen is Origen.NINGUNO
    assert transcurrido < LATENCIA_DEGRADADO / 2
    assert adaptador.breaker.estado is EstadoCircuito.CERRADO  # el sondeo largo sí alcanzó


def test_las_metricas_exponen_latencia_tasa_de_error_y_circuito(adaptador, proveedor):
    adaptador.perfil_de(CLIENTE, CONSENTIMIENTO)
    abrir_circuito(adaptador, proveedor)

    resumen = adaptador.resumen()

    assert resumen["circuito"]["estado"] == "abierto"
    assert resumen["circuito"]["aperturas"] == 1
    assert (
        resumen["latencia_y_errores"]["llamadas"] == 5
    )  # la quinta ya encontró el circuito abierto
    assert resumen["latencia_y_errores"]["errores"] == 4
    assert resumen["latencia_y_errores"]["p95_ms"] >= 0
    assert resumen["presupuesto_ms"] == 50


# --- Servicio ---------------------------------------------------------------------------------


def test_el_servicio_no_consulta_sin_consentimiento(adaptador, proveedor):
    servicio = ServicioDePerfilamiento(adaptador)

    with pytest.raises(PerfilSinConsentimientoError):
        servicio.perfil_de(CLIENTE, "")
    assert proveedor.llamadas == 0


# --- Integración: cotización, /salud y revocación ----------------------------------------------


@pytest.fixture
def nucleo(monkeypatch) -> TestClient:
    monkeypatch.delenv(auditoria_dependencias.VARIABLE_DSN, raising=False)
    monkeypatch.delenv(dependencias.VARIABLE_MODO, raising=False)

    def reiniciar():
        auditoria_dependencias.reiniciar()
        consentimiento_dependencias.reiniciar()
        dependencias.reiniciar()
        cotizacion_dependencias._componentes.cache_clear()

    reiniciar()
    yield TestClient(crear_app())
    reiniciar()


def test_cotizacion_tarifa_con_el_perfil_real_y_degrada_a_cache_y_a_estandar(nucleo):
    ruta_consent = f"/v1/consentimiento/clientes/{CLIENTE}/consentimientos"
    id_consentimiento = nucleo.post(
        ruta_consent,
        json={
            "finalidad": "cotizacion",
            "entidades_origen": ["ent-andes"],
            "version_texto": "cotizacion-2026.1",
        },
    ).json()["id"]

    # Cada llamada cambia la edad: la misma solicitud, con la oferta vigente, devolvería la
    # misma cotización (WEB-F01, criterio 4) y no volvería a pasar por el proveedor.
    edades = itertools.count(30)

    def cotizar() -> str:
        respuesta = nucleo.post(
            "/v1/cotizacion/cotizaciones",
            json={
                "producto": "auto",
                "asegurado": {"edad": next(edades), "ciudad": "Bogotá"},
                "id_cliente": CLIENTE,
            },
        )
        assert respuesta.status_code == 201
        id_cotizacion = respuesta.json()["id_cotizacion"]
        return nucleo.get(f"/v1/cotizacion/cotizaciones/{id_cotizacion}/explicacion").json()[
            "base_perfilamiento"
        ]

    assert cotizar() == "open_finance"

    dependencias.obtener_adaptador().proveedor.modo = Modo.CAIDO
    assert cotizar() == "open_finance_en_cache"
    for _ in range(4):
        cotizar()
    salud = nucleo.get("/salud").json()
    assert salud["estado"] == "degradado"
    assert (
        salud["modulos"]["perfilamiento"]["dependencias"]["open_finance"]["estado"] == "degradado"
    )
    assert nucleo.get("/v1/perfilamiento/metricas").json()["circuito"]["estado"] == "abierto"

    nucleo.post(f"{ruta_consent}/{id_consentimiento}/revocacion")
    assert cotizar() == "estandar"
    assert dependencias.obtener_cache().obtener(CLIENTE) is None


def test_salud_reporta_el_adaptador_con_el_proveedor_sano(nucleo):
    detalle = nucleo.get("/salud").json()["modulos"]["perfilamiento"]["dependencias"][
        "open_finance"
    ]

    assert detalle["estado"] == "simulado"
    assert "circuito cerrado" in detalle["detalle"]
