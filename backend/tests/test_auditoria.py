"""Pruebas de ARQ-03: log de auditoría append-only con cadena de hash.

Cubren el dominio, el almacén en memoria, la cola de escritura y el contrato HTTP.
Las tres capas de protección en la base de datos se prueban en
`test_auditoria_postgres.py`, que necesita un PostgreSQL real.
"""

from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
from datetime import UTC, datetime
from decimal import Decimal

import pytest
from fastapi.testclient import TestClient

from app.main import crear_app
from app.modulos.auditoria import EventoAuditable, ServicioDeAuditoria
from app.modulos.auditoria.api import dependencias
from app.modulos.auditoria.dominio import cadena
from app.modulos.auditoria.infraestructura.almacen_memoria import AlmacenEnMemoria
from app.modulos.auditoria.infraestructura.cola import ColaDeEscritura

INSTANTE = datetime(2026, 10, 17, 15, 0, 0, tzinfo=UTC)


def decision(n: int = 1, **cambios: object) -> EventoAuditable:
    base = dict(
        tipo="decision_cotizacion",
        actor="CLI-0001",
        canal="api-v1",
        ocurrido_en=INSTANTE,
        version_regla="rating-v3.2",
        variables_entrada={"edad": 34, "prima_estandar": 148000, "factor": Decimal("-0.10")},
        resultado={"prima_mensual": 133200 + n},
        referencias={"id_cotizacion": f"QT-{n:04d}", "id_consentimiento": "CONSENT-1"},
    )
    base.update(cambios)
    return EventoAuditable(**base)  # type: ignore[arg-type]


@pytest.fixture
def almacen() -> AlmacenEnMemoria:
    return AlmacenEnMemoria()


@pytest.fixture
def servicio(almacen) -> ServicioDeAuditoria:
    return ServicioDeAuditoria(almacen)


# --- Criterio 1: cada decisión deja un registro completo -----------------------------------


def test_una_decision_queda_registrada_con_todos_sus_campos(servicio):
    id_decision = servicio.registrar(decision())

    registro = servicio.reconstruir(id_decision)
    assert registro.id_decision == id_decision
    assert registro.secuencia == 1
    assert registro.evento.version_regla == "rating-v3.2"
    assert registro.evento.variables_entrada["edad"] == 34
    assert registro.evento.resultado == {"prima_mensual": 133201}
    assert registro.evento.actor == "CLI-0001"
    assert registro.evento.canal == "api-v1"
    assert registro.evento.ocurrido_en == INSTANTE


def test_un_evento_sin_zona_horaria_se_rechaza():
    with pytest.raises(ValueError, match="zona horaria"):
        decision(ocurrido_en=datetime(2026, 10, 17, 15, 0, 0))


def test_reconstruir_una_decision_inexistente_es_recurso_no_encontrado(servicio):
    from app.compartido.errores import RecursoNoEncontrado

    with pytest.raises(RecursoNoEncontrado):
        servicio.reconstruir("DEC-no-existe")


# --- Criterio 6: los eventos de consentimiento guardan la versión del texto legal ---------


def test_un_evento_de_consentimiento_guarda_la_version_del_texto_legal(servicio):
    id_decision = servicio.registrar(
        decision(
            tipo="consentimiento_otorgado",
            version_regla=None,
            variables_entrada={"finalidad": "cotizacion"},
            resultado={"estado": "vigente"},
            version_texto_legal="consentimiento-of-2026-03",
        )
    )
    assert (
        servicio.reconstruir(id_decision).evento.version_texto_legal == "consentimiento-of-2026-03"
    )


# --- Criterio 3: la cadena completa verifica sin eslabones rotos ---------------------------


def test_la_cadena_enlaza_cada_registro_con_el_anterior(servicio, almacen):
    for n in range(1, 4):
        servicio.registrar(decision(n))

    registros = almacen.todos()
    assert [r.secuencia for r in registros] == [1, 2, 3]
    assert registros[0].hash_anterior == cadena.HASH_GENESIS
    assert registros[1].hash_anterior == registros[0].hash
    assert registros[2].hash_anterior == registros[1].hash
    informe = servicio.verificar_integridad()
    assert informe.total == 3
    assert informe.conforme


def test_el_hash_cubre_la_secuencia():
    evento = decision()
    assert cadena.calcular_hash(1, cadena.HASH_GENESIS, "DEC-1", evento) != cadena.calcular_hash(
        2, cadena.HASH_GENESIS, "DEC-1", evento
    )


def test_el_hash_no_depende_del_orden_de_las_claves_ni_de_la_zona_horaria():
    from datetime import timedelta, timezone

    bogota = timezone(timedelta(hours=-5))
    a = decision(variables_entrada={"a": 1, "b": 2})
    b = decision(variables_entrada={"b": 2, "a": 1}, ocurrido_en=INSTANTE.astimezone(bogota))
    assert cadena.calcular_hash(1, cadena.HASH_GENESIS, "DEC-1", a) == cadena.calcular_hash(
        1, cadena.HASH_GENESIS, "DEC-1", b
    )


# --- Criterio 4: un registro alterado se señala, y solo ese --------------------------------


def test_un_registro_alterado_se_senala_exactamente(servicio, almacen):
    for n in range(1, 6):
        servicio.registrar(decision(n))
    registros = list(almacen.todos())
    alterado = registros[2]
    registros[2] = replace(
        alterado, evento=replace(alterado.evento, resultado={"prima_mensual": 1})
    )

    informe = cadena.verificar(registros)

    assert not informe.conforme
    assert [(e.secuencia, e.motivo) for e in informe.eslabones_rotos] == [(3, "hash_no_coincide")]


def test_un_registro_borrado_deja_la_secuencia_discontinua(servicio, almacen):
    for n in range(1, 4):
        servicio.registrar(decision(n))
    registros = [r for r in almacen.todos() if r.secuencia != 2]

    informe = cadena.verificar(registros)

    assert [(e.secuencia, e.motivo) for e in informe.eslabones_rotos] == [
        (3, "secuencia_discontinua")
    ]


def test_un_registro_insertado_rompe_el_hash_anterior_del_siguiente(servicio, almacen):
    servicio.registrar(decision(1))
    servicio.registrar(decision(2))
    primero, segundo = almacen.todos()
    intruso = cadena.encadenar(1, cadena.HASH_GENESIS, "DEC-intruso", decision(99))

    informe = cadena.verificar([intruso, segundo])

    assert [(e.secuencia, e.motivo) for e in informe.eslabones_rotos] == [
        (2, "hash_anterior_no_coincide")
    ]


# --- Criterio 5: veinte escrituras concurrentes dejan la cadena consistente ---------------


def test_veinte_escrituras_concurrentes_mantienen_la_cadena(servicio):
    with ThreadPoolExecutor(max_workers=20) as hilos:
        ids = list(hilos.map(lambda n: servicio.registrar(decision(n)), range(20)))

    informe = servicio.verificar_integridad()
    assert informe.total == 20
    assert informe.conforme
    assert len(set(ids)) == 20


# --- La escritura se difiere a un hilo propio -----------------------------------------------


def test_la_cola_escribe_en_segundo_plano_y_en_orden(almacen):
    cola = ColaDeEscritura()
    servicio = ServicioDeAuditoria(almacen, ejecutor=cola)

    ids = [servicio.registrar(decision(n)) for n in range(5)]
    cola.vaciar()

    assert [r.id_decision for r in almacen.todos()] == ids
    assert servicio.verificar_integridad().conforme


def test_un_fallo_al_escribir_no_detiene_la_cola(caplog):
    class AlmacenQueFalla(AlmacenEnMemoria):
        def anexar(self, id_decision, evento):
            if evento.resultado["prima_mensual"] == 133201:
                raise RuntimeError("base caída")
            return super().anexar(id_decision, evento)

    almacen = AlmacenQueFalla()
    cola = ColaDeEscritura()
    servicio = ServicioDeAuditoria(almacen, ejecutor=cola)

    servicio.registrar(decision(1))
    servicio.registrar(decision(2))
    cola.vaciar()

    assert len(almacen.todos()) == 1
    assert "No se pudo escribir" in caplog.text


# --- Contrato HTTP y uso desde cotización ---------------------------------------------------


@pytest.fixture
def cliente(monkeypatch):
    monkeypatch.delenv(dependencias.VARIABLE_DSN, raising=False)
    dependencias.reiniciar()
    from app.modulos.cotizacion.api import dependencias as cotizacion

    cotizacion._componentes.cache_clear()
    yield TestClient(crear_app())
    dependencias.reiniciar()
    cotizacion._componentes.cache_clear()


def test_una_cotizacion_deja_su_decision_en_el_log_real(cliente):
    cliente.post(
        "/v1/cotizacion/cotizaciones",
        json={"producto": "auto", "asegurado": {"edad": 34, "ciudad": "Bogotá"}},
    )
    dependencias.vaciar_escrituras_pendientes()

    informe = cliente.get("/v1/auditoria/integridad").json()
    assert informe == {"total": 1, "conforme": True, "eslabones_rotos": []}
    registro = dependencias.obtener_servicio().verificar_integridad()
    assert registro.total == 1
    ultimo = cliente.get("/salud").json()["modulos"]["auditoria"]
    assert ultimo["estado"] == "ok"
    assert ultimo["dependencias"]["almacen_auditoria"]["estado"] == "simulado"


def test_al_apagar_el_nucleo_se_escribe_lo_que_quedo_en_la_cola(cliente):
    with cliente:
        id_decision = dependencias.obtener_servicio().registrar(decision())

    assert dependencias.obtener_servicio().reconstruir(id_decision).id_decision == id_decision


def test_apagar_sin_haber_usado_auditoria_no_arma_el_modulo(monkeypatch):
    monkeypatch.delenv(dependencias.VARIABLE_DSN, raising=False)
    dependencias.reiniciar()

    dependencias.vaciar_escrituras_pendientes()

    assert not dependencias._componentes.creada


def test_reconstruir_por_http_devuelve_el_registro_completo(cliente):
    id_decision = dependencias.obtener_servicio().registrar(decision())
    dependencias.vaciar_escrituras_pendientes()

    respuesta = cliente.get(f"/v1/auditoria/registros/{id_decision}")

    assert respuesta.status_code == 200
    cuerpo = respuesta.json()
    assert cuerpo["id_decision"] == id_decision
    assert cuerpo["version_regla"] == "rating-v3.2"
    assert cuerpo["hash_anterior"] == cadena.HASH_GENESIS
    assert len(cuerpo["hash"]) == 64


def test_reconstruir_una_decision_inexistente_responde_404_problem_json(cliente):
    respuesta = cliente.get("/v1/auditoria/registros/DEC-nada")

    assert respuesta.status_code == 404
    assert respuesta.headers["content-type"].startswith("application/problem+json")
    assert respuesta.json()["title"] == "recurso_no_encontrado"
