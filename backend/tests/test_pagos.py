"""Pruebas de WEB-F03, criterio 2: el pago no se cobra dos veces (P18).

Contra el repositorio en memoria y la pasarela simulada. Las mismas reglas contra PostgreSQL
están en `test_pagos_postgres.py`.
"""

from __future__ import annotations

import threading
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime

import pytest

from app.compartido.dinero import Dinero
from app.compartido.errores import DependenciaNoDisponible
from app.compartido.reloj import RelojFijo
from app.modulos.pagos.aplicacion.servicio import ServicioDePagos
from app.modulos.pagos.dominio.errores import (
    ClaveDeOtroPagoError,
    PagoEnCursoError,
    PagoNoEncontradoError,
    PagoYaRegistradoError,
)
from app.modulos.pagos.dominio.modelo import EstadoPago, MedioPago, RespuestaPasarela
from app.modulos.pagos.infraestructura.pasarela_simulada import PasarelaSimulada
from app.modulos.pagos.infraestructura.repositorio_memoria import RepositorioEnMemoria

INSTANTE = datetime(2026, 11, 8, 15, 0, tzinfo=UTC)
CLIENTE = "CLI-2026-000318"
SUSCRIPCION = "SUS-1"
PRIMA = Dinero.cop(132_900)


class AuditoriaEnMemoria:
    def __init__(self) -> None:
        self.registros: list[tuple[str, EstadoPago]] = []

    def registrar(self, pago) -> str:
        self.registros.append((pago.id_pago, pago.estado))
        return f"DEC-{len(self.registros)}"


@pytest.fixture
def pasarela() -> PasarelaSimulada:
    return PasarelaSimulada()


@pytest.fixture
def auditoria() -> AuditoriaEnMemoria:
    return AuditoriaEnMemoria()


@pytest.fixture
def repositorio() -> RepositorioEnMemoria:
    return RepositorioEnMemoria()


@pytest.fixture
def servicio(repositorio, pasarela, auditoria) -> ServicioDePagos:
    return ServicioDePagos(repositorio, pasarela, auditoria, RelojFijo(INSTANTE))


def _cobrar(servicio, clave="clave-1", medio=MedioPago.TARJETA_CREDITO, concepto=SUSCRIPCION):
    return servicio.cobrar(concepto, CLIENTE, PRIMA, medio, clave, "web")


def test_la_tarjeta_se_aprueba_y_queda_auditada(servicio, pasarela, auditoria):
    pago, nuevo = _cobrar(servicio)

    assert nuevo
    assert pago.estado is EstadoPago.APROBADO
    assert pago.referencia.startswith("TX-")
    assert pago.monto == PRIMA
    assert pago.id_decision == "DEC-1"
    assert auditoria.registros == [(pago.id_pago, EstadoPago.APROBADO)]
    assert len(pasarela.cobros) == 1


def test_la_misma_clave_no_cobra_otra_vez(servicio, pasarela, auditoria):
    primero, _ = _cobrar(servicio)

    repetido, nuevo = _cobrar(servicio)

    assert not nuevo
    assert repetido == primero
    assert len(pasarela.cobros) == 1
    assert len(auditoria.registros) == 1


def test_otra_clave_con_un_pago_aprobado_es_pago_ya_registrado(servicio, pasarela):
    _cobrar(servicio)

    with pytest.raises(PagoYaRegistradoError) as error:
        _cobrar(servicio, clave="clave-2")

    assert error.value.codigo == "pago_ya_registrado"
    assert len(pasarela.cobros) == 1


def test_tras_un_rechazo_se_puede_intentar_con_otra_clave(servicio, pasarela):
    pasarela.resultados[MedioPago.TARJETA_CREDITO] = EstadoPago.RECHAZADO
    rechazado, _ = _cobrar(servicio)
    pasarela.resultados[MedioPago.TARJETA_CREDITO] = EstadoPago.APROBADO

    aprobado, nuevo = _cobrar(servicio, clave="clave-2")

    assert rechazado.estado is EstadoPago.RECHAZADO
    assert nuevo and aprobado.estado is EstadoPago.APROBADO
    assert servicio.ultimo(SUSCRIPCION) == aprobado


def test_la_misma_clave_de_otro_concepto_es_invalida(servicio):
    _cobrar(servicio)

    with pytest.raises(ClaveDeOtroPagoError):
        _cobrar(servicio, concepto="SUS-2")


def test_la_misma_clave_mientras_la_pasarela_responde_es_pago_en_curso(repositorio, auditoria):
    """El segundo envío llega antes de que la pasarela responda al primero."""
    liberar = threading.Event()
    adentro = threading.Event()

    class PasarelaLenta(PasarelaSimulada):
        def cobrar(self, id_pago, monto, medio):
            adentro.set()
            liberar.wait(5)
            return super().cobrar(id_pago, monto, medio)

    servicio = ServicioDePagos(repositorio, PasarelaLenta(), auditoria, RelojFijo(INSTANTE))
    with ThreadPoolExecutor(1) as hilo:
        primero = hilo.submit(_cobrar, servicio)
        assert adentro.wait(5)
        with pytest.raises(PagoEnCursoError):
            _cobrar(servicio)
        liberar.set()
        pago, _ = primero.result()

    assert pago.estado is EstadoPago.APROBADO


def test_envios_simultaneos_con_claves_distintas_cobran_una_sola_vez(servicio, pasarela):
    def intentar(i):
        try:
            return _cobrar(servicio, clave=f"clave-{i}")[0]
        except (PagoYaRegistradoError, PagoEnCursoError):
            return None

    with ThreadPoolExecutor(8) as hilos:
        resultados = list(hilos.map(intentar, range(16)))

    assert len([r for r in resultados if r is not None]) == 1
    assert len(pasarela.cobros) == 1


def test_pse_queda_pendiente_y_se_confirma_al_consultar(servicio, auditoria):
    pago, _ = _cobrar(servicio, medio=MedioPago.PSE)
    assert pago.estado is EstadoPago.PENDIENTE

    confirmado = servicio.consultar(SUSCRIPCION, pago.id_pago)

    assert confirmado.estado is EstadoPago.APROBADO
    assert confirmado.referencia == pago.referencia
    assert [estado for _, estado in auditoria.registros] == [
        EstadoPago.PENDIENTE,
        EstadoPago.APROBADO,
    ]
    # Ya resuelto, consultar otra vez no vuelve a auditar.
    assert servicio.consultar(SUSCRIPCION, pago.id_pago) == confirmado
    assert len(auditoria.registros) == 2


def test_un_pendiente_que_sigue_pendiente_no_se_audita_de_nuevo(repositorio, auditoria):
    class SiemprePendiente(PasarelaSimulada):
        def consultar(self, referencia):
            return RespuestaPasarela(EstadoPago.PENDIENTE, referencia)

    servicio = ServicioDePagos(repositorio, SiemprePendiente(), auditoria, RelojFijo(INSTANTE))
    pago, _ = _cobrar(servicio, medio=MedioPago.PSE)

    assert servicio.consultar(SUSCRIPCION, pago.id_pago).estado is EstadoPago.PENDIENTE
    assert len(auditoria.registros) == 1


def test_un_pago_de_otro_concepto_no_existe(servicio):
    pago, _ = _cobrar(servicio)

    with pytest.raises(PagoNoEncontradoError):
        servicio.consultar("SUS-OTRA", pago.id_pago)
    with pytest.raises(PagoNoEncontradoError):
        servicio.consultar(SUSCRIPCION, "PAG-NO-EXISTE")


def test_con_la_pasarela_caida_no_hay_cobro_y_se_puede_reintentar(servicio, pasarela):
    pasarela.caida = True
    with pytest.raises(DependenciaNoDisponible):
        _cobrar(servicio)
    assert servicio.ultimo(SUSCRIPCION) is None

    pasarela.caida = False
    pago, nuevo = _cobrar(servicio)

    assert nuevo and pago.estado is EstadoPago.APROBADO


def test_la_pasarela_se_configura_por_entorno():
    pasarela = PasarelaSimulada.desde_entorno(
        {
            "PASARELA_TARJETA_CREDITO": "rechazado",
            "PASARELA_PSE": "aprobado",
            "PASARELA_PENDIENTE_SE_RESUELVE_EN": "rechazado",
            "PASARELA_MODO": "caida",
        }
    )

    assert pasarela.resultados == {
        MedioPago.TARJETA_CREDITO: EstadoPago.RECHAZADO,
        MedioPago.PSE: EstadoPago.APROBADO,
    }
    assert pasarela.pendiente_se_resuelve_en is EstadoPago.RECHAZADO
    assert pasarela.caida
    with pytest.raises(DependenciaNoDisponible):
        pasarela.consultar("TX-1")


@pytest.mark.parametrize(
    "entorno",
    [{"PASARELA_MODO": "rota"}, {"PASARELA_PSE": "procesando"}, {"PASARELA_PSE": "otro"}],
)
def test_una_configuracion_invalida_no_arranca(entorno):
    with pytest.raises(RuntimeError):
        PasarelaSimulada.desde_entorno(entorno)


# -- fallos a mitad del cobro: ni cobro doble ni pago colgado (revisión de #270) ----------


class _PasarelaQueCobraYNoResponde(PasarelaSimulada):
    """Cobra, pero la respuesta se pierde (timeout de lectura): no es `DependenciaNoDisponible`."""

    def cobrar(self, id_pago, monto, medio):
        super().cobrar(id_pago, monto, medio)
        raise TimeoutError("se perdió la respuesta")


def test_un_error_que_no_garantiza_que_no_hubo_cobro_no_libera_la_reserva(repositorio, auditoria):
    reloj = RelojFijo(INSTANTE)
    pasarela = _PasarelaQueCobraYNoResponde()
    servicio = ServicioDePagos(repositorio, pasarela, auditoria, reloj)
    with pytest.raises(TimeoutError):
        _cobrar(servicio)

    with pytest.raises(PagoEnCursoError):
        _cobrar(servicio)

    assert len(pasarela.cobros) == 1


def test_un_pago_colgado_se_concilia_con_lo_que_cobro_la_pasarela(repositorio, auditoria):
    reloj = RelojFijo(INSTANTE)
    pasarela = _PasarelaQueCobraYNoResponde()
    servicio = ServicioDePagos(repositorio, pasarela, auditoria, reloj)
    with pytest.raises(TimeoutError):
        _cobrar(servicio)
    reloj.avanzar(61)

    pago, nuevo = _cobrar(servicio)

    assert not nuevo
    assert pago.estado is EstadoPago.APROBADO and pago.id_decision is not None
    assert len(pasarela.cobros) == 1
    assert repositorio.obtener(pago.id_pago).estado is EstadoPago.APROBADO


def test_un_pago_colgado_tambien_se_concilia_con_otra_clave(repositorio, auditoria):
    reloj = RelojFijo(INSTANTE)
    pasarela = _PasarelaQueCobraYNoResponde()
    servicio = ServicioDePagos(repositorio, pasarela, auditoria, reloj)
    with pytest.raises(TimeoutError):
        _cobrar(servicio)
    reloj.avanzar(61)

    with pytest.raises(PagoYaRegistradoError) as error:
        _cobrar(servicio, clave="clave-2")

    assert error.value.detalles["estado"] is EstadoPago.APROBADO
    assert len(pasarela.cobros) == 1


def test_si_la_auditoria_falla_tras_cobrar_el_pago_se_concilia_despues(repositorio, pasarela):
    class AuditoriaQueFallaUnaVez(AuditoriaEnMemoria):
        fallar = True

        def registrar(self, pago) -> str:
            if self.fallar:
                self.fallar = False
                raise RuntimeError("auditoría caída")
            return super().registrar(pago)

    reloj = RelojFijo(INSTANTE)
    servicio = ServicioDePagos(repositorio, pasarela, AuditoriaQueFallaUnaVez(), reloj)
    with pytest.raises(RuntimeError):
        _cobrar(servicio)
    reloj.avanzar(61)

    pago, _ = _cobrar(servicio)

    assert pago.estado is EstadoPago.APROBADO and pago.id_decision == "DEC-1"
    assert len(pasarela.cobros) == 1


def test_un_pago_colgado_que_la_pasarela_nunca_recibio_se_libera(repositorio, pasarela, auditoria):
    reloj = RelojFijo(INSTANTE)
    servicio = ServicioDePagos(repositorio, pasarela, auditoria, reloj)
    servicio_que_muere = ServicioDePagos(repositorio, pasarela, auditoria, reloj)
    # El proceso murió entre la reserva y la llamada a la pasarela.
    servicio_que_muere._cobrar_reservado = lambda reservado: (_ for _ in ()).throw(SystemExit)
    with pytest.raises(SystemExit):
        _cobrar(servicio_que_muere)
    reloj.avanzar(61)

    pago, nuevo = _cobrar(servicio)

    assert nuevo and pago.estado is EstadoPago.APROBADO
    assert len(pasarela.cobros) == 1


def test_leer_un_pago_no_llama_a_la_pasarela_y_exige_el_cliente(servicio, pasarela):
    pendiente, _ = _cobrar(servicio, medio=MedioPago.PSE)

    leido = servicio.leer(CLIENTE, SUSCRIPCION, pendiente.id_pago)

    assert leido.estado is EstadoPago.PENDIENTE
    with pytest.raises(PagoNoEncontradoError):
        servicio.leer("CLI-OTRO", SUSCRIPCION, pendiente.id_pago)


def test_ultimos_trae_el_ultimo_intento_de_cada_concepto(servicio, pasarela):
    pasarela.resultados[MedioPago.TARJETA_CREDITO] = EstadoPago.RECHAZADO
    _cobrar(servicio, clave="clave-1")
    pasarela.resultados[MedioPago.TARJETA_CREDITO] = EstadoPago.APROBADO
    segundo, _ = _cobrar(servicio, clave="clave-2")
    otro, _ = _cobrar(servicio, clave="clave-3", concepto="SUS-2")

    ultimos = servicio.ultimos(["SUS-1", "SUS-2", "SUS-3"])

    assert ultimos == {"SUS-1": segundo, "SUS-2": otro}


def test_una_pasarela_mal_configurada_impide_armar_el_nucleo(monkeypatch):
    from app.main import crear_app

    monkeypatch.setenv("PASARELA_MODO", "rota")

    with pytest.raises(RuntimeError, match="PASARELA_MODO"):
        crear_app()
