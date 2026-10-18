"""Pruebas de WEB-F02: servicio de consentimiento Open Finance (backend).

Una sección por criterio de aceptación de la historia. Los criterios 1 y 9 son de
interfaz; aquí se prueba lo que el backend aporta a cada uno.
"""

from __future__ import annotations

import threading
import time
from datetime import UTC, datetime, timedelta

import pytest
from fastapi.testclient import TestClient

from app.compartido.errores import RecursoNoEncontrado
from app.compartido.eventos import BusEnProceso, obtener_bus
from app.compartido.reloj import RelojFijo
from app.compartido.unica import instancia_unica
from app.main import crear_app
from app.modulos import perfilamiento
from app.modulos.auditoria.api import dependencias as auditoria_dependencias
from app.modulos.auditoria.aplicacion.servicio import ServicioDeAuditoria
from app.modulos.auditoria.infraestructura.almacen_memoria import AlmacenEnMemoria
from app.modulos.consentimiento.api import dependencias
from app.modulos.consentimiento.aplicacion.servicio import (
    EVENTO_REVOCADO,
    ServicioDeConsentimiento,
)
from app.modulos.consentimiento.dominio.errores import (
    ConsentimientoNoVigenteError,
    ConsentimientoRevocadoError,
    ConsentimientoYaVigenteError,
    EntidadesInvalidasError,
    VersionDeTextoDesactualizadaError,
)
from app.modulos.consentimiento.dominio.modelo import Estado, TipoEvento, sumar_meses
from app.modulos.consentimiento.infraestructura.auditoria_nucleo import RegistroEnAuditoria
from app.modulos.consentimiento.infraestructura.repositorio_memoria import RepositorioEnMemoria
from app.modulos.cotizacion.api import dependencias as cotizacion_dependencias
from app.modulos.perfilamiento.api import dependencias as perfilamiento_dependencias

INSTANTE = datetime(2026, 10, 17, 15, 0, 0, tzinfo=UTC)
CLIENTE = "CLI-0001"
OTRO_CLIENTE = "CLI-0002"
SOLICITUD = {
    "finalidad": "cotizacion",
    "entidades_origen": ["ent-andes"],
    "version_texto": "cotizacion-2026.1",
}
RUTA = f"/v1/consentimiento/clientes/{CLIENTE}/consentimientos"


@pytest.fixture
def reloj() -> RelojFijo:
    return RelojFijo(INSTANTE)


@pytest.fixture
def almacen_auditoria() -> AlmacenEnMemoria:
    return AlmacenEnMemoria()


@pytest.fixture
def bus() -> BusEnProceso:
    return BusEnProceso()


@pytest.fixture
def servicio(reloj, almacen_auditoria, bus) -> ServicioDeConsentimiento:
    return ServicioDeConsentimiento(
        repositorio=RepositorioEnMemoria(),
        auditoria=RegistroEnAuditoria(ServicioDeAuditoria(almacen_auditoria)),
        bus=bus,
        reloj=reloj,
    )


def otorgar(servicio, cliente: str = CLIENTE, canal: str = "web", **cambios):
    return servicio.otorgar(id_cliente=cliente, canal=canal, **{**SOLICITUD, **cambios})


@pytest.fixture
def cliente(servicio, reloj) -> TestClient:
    """App con el servicio y el reloj bajo control de la prueba."""
    app = crear_app()
    app.dependency_overrides[dependencias.obtener_servicio] = lambda: servicio
    app.dependency_overrides[dependencias.obtener_reloj] = lambda: reloj
    return TestClient(app)


@pytest.fixture
def nucleo(monkeypatch) -> TestClient:
    """App con la composición real de todos los módulos, reiniciada antes y después."""
    monkeypatch.delenv(auditoria_dependencias.VARIABLE_DSN, raising=False)

    def reiniciar():
        auditoria_dependencias.reiniciar()
        dependencias.reiniciar()
        cotizacion_dependencias._componentes.cache_clear()
        perfilamiento_dependencias.obtener_cache.cache_clear()

    reiniciar()
    yield TestClient(crear_app())
    reiniciar()


# --- Criterio 1: cada finalidad por separado, con alcance, vigencia y entidades -----------


def test_el_catalogo_tiene_las_tres_finalidades_completas(cliente):
    finalidades = cliente.get("/v1/consentimiento/finalidades").json()

    assert [f["codigo"] for f in finalidades] == ["cotizacion", "suscripcion", "prevencion_fraude"]
    for finalidad in finalidades:
        assert finalidad["alcance"]
        assert finalidad["vigencia_meses"] >= 1
        assert [e["id"] for e in finalidad["entidades_origen"]] == ["ent-andes", "ent-sur"]
        assert finalidad["version_texto"].endswith("2026.1")


def test_un_cliente_nuevo_no_tiene_nada_otorgado(cliente):
    assert cliente.get(RUTA).json() == []


# --- Criterio 2: otorgar registra finalidad, alcance, vigencia, canal y fecha ------------


def test_otorgar_deja_el_consentimiento_vigente_con_su_vencimiento(servicio):
    consentimiento = otorgar(servicio)

    assert consentimiento.estado(INSTANTE) is Estado.VIGENTE
    assert consentimiento.finalidad == "cotizacion"
    assert consentimiento.entidades_origen == ("ent-andes",)
    assert consentimiento.canal == "web"
    assert consentimiento.otorgado_en == INSTANTE
    assert consentimiento.vence_en == sumar_meses(INSTANTE, 12)
    assert consentimiento.version_texto == "cotizacion-2026.1"
    assert [e.tipo for e in consentimiento.eventos] == [TipoEvento.OTORGADO]


def test_otorgar_por_http_responde_201_con_el_estado_vigente(cliente):
    respuesta = cliente.post(RUTA, json=SOLICITUD, headers={"X-Canal": "movil"})

    assert respuesta.status_code == 201
    cuerpo = respuesta.json()
    assert cuerpo["estado"] == "vigente"
    assert cuerpo["canal"] == "movil"
    assert cuerpo["vence_en"] == sumar_meses(INSTANTE, 12).isoformat().replace("+00:00", "Z")
    assert cuerpo["entidades_origen"] == [{"id": "ent-andes", "nombre": "Banco Andes S.A."}]
    assert cuerpo["por_vencer"] is False


def test_no_se_otorga_dos_veces_la_misma_finalidad(servicio):
    otorgar(servicio)
    with pytest.raises(ConsentimientoYaVigenteError):
        otorgar(servicio)


def test_cada_finalidad_se_otorga_por_separado(servicio):
    otorgar(servicio)
    otorgar(servicio, finalidad="suscripcion", version_texto="suscripcion-2026.1")

    assert [c.finalidad for c in servicio.listar(CLIENTE)] == ["cotizacion", "suscripcion"]


def test_el_texto_legal_aceptado_debe_ser_el_vigente(servicio):
    with pytest.raises(VersionDeTextoDesactualizadaError) as error:
        otorgar(servicio, version_texto="cotizacion-2025.9")
    assert error.value.detalles["version_vigente"] == "cotizacion-2026.1"


@pytest.mark.parametrize("entidades", [[], ["ent-desconocida"], ["ent-andes", "ent-x"]])
def test_las_entidades_deben_ser_del_catalogo(servicio, entidades):
    with pytest.raises(EntidadesInvalidasError):
        otorgar(servicio, entidades_origen=entidades)


def test_una_finalidad_desconocida_es_422_por_contrato(cliente):
    respuesta = cliente.post(RUTA, json={**SOLICITUD, "finalidad": "marketing"})
    assert respuesta.status_code == 422


# --- Criterio 3: la revocación es inmediata y se propaga ----------------------------------


def test_revocar_cambia_el_estado_de_inmediato_y_publica_el_evento(servicio, bus):
    consentimiento = otorgar(servicio)
    inicio = time.perf_counter()

    revocado = servicio.revocar(CLIENTE, consentimiento.id_consentimiento, canal="web")
    vigencia = servicio.consentimiento_vigente(CLIENTE, "cotizacion")

    assert revocado.estado(INSTANTE) is Estado.REVOCADO
    assert revocado.revocado_en == INSTANTE
    assert vigencia.vigente is False
    assert time.perf_counter() - inicio < 300  # RC-04 pide ≤ 5 minutos; aquí es inmediato
    publicados = bus.publicados(EVENTO_REVOCADO)
    assert len(publicados) == 1
    assert publicados[0].carga["id_cliente"] == CLIENTE
    assert publicados[0].carga["id_consentimiento"] == consentimiento.id_consentimiento


def test_solo_se_revoca_un_consentimiento_vigente(servicio):
    consentimiento = otorgar(servicio)
    servicio.revocar(CLIENTE, consentimiento.id_consentimiento, canal="web")

    with pytest.raises(ConsentimientoNoVigenteError):
        servicio.revocar(CLIENTE, consentimiento.id_consentimiento, canal="web")


def test_un_consentimiento_ajeno_no_existe_para_otro_cliente(servicio):
    consentimiento = otorgar(servicio)

    with pytest.raises(RecursoNoEncontrado):
        servicio.revocar(OTRO_CLIENTE, consentimiento.id_consentimiento, canal="web")
    with pytest.raises(RecursoNoEncontrado):
        servicio.obtener(OTRO_CLIENTE, consentimiento.id_consentimiento)


def test_revocar_por_http_y_repetirlo_responde_409_problem_json(cliente):
    id_consentimiento = cliente.post(RUTA, json=SOLICITUD).json()["id"]

    primera = cliente.post(f"{RUTA}/{id_consentimiento}/revocacion")
    segunda = cliente.post(f"{RUTA}/{id_consentimiento}/revocacion")

    assert primera.status_code == 200
    assert primera.json()["estado"] == "revocado"
    assert segunda.status_code == 409
    assert segunda.headers["content-type"].startswith("application/problem+json")
    assert segunda.json()["title"] == "consentimiento_no_vigente"


# --- Criterio 4: revocado, la cotización cae a tarifa estándar ------------------------------


def test_tras_revocar_la_cotizacion_usa_tarifa_estandar(nucleo):
    solicitud = {
        "producto": "auto",
        "asegurado": {"edad": 34, "ciudad": "Bogotá"},
        "id_cliente": CLIENTE,
    }

    def cotizar():
        respuesta = nucleo.post("/v1/cotizacion/cotizaciones", json=solicitud)
        assert respuesta.status_code == 201
        cuerpo = respuesta.json()
        explicacion = nucleo.get(
            f"/v1/cotizacion/cotizaciones/{cuerpo['id_cotizacion']}/explicacion"
        ).json()
        return cuerpo["id_consentimiento"], explicacion["base_perfilamiento"]

    assert cotizar() == (None, "estandar")

    id_consentimiento = nucleo.post(RUTA, json=SOLICITUD).json()["id"]
    assert cotizar() == (id_consentimiento, "open_finance")

    nucleo.post(f"{RUTA}/{id_consentimiento}/revocacion")
    assert cotizar() == (None, "estandar")


# --- Criterio 5: la caché de perfil queda vacía ---------------------------------------------


def test_la_revocacion_vacia_la_cache_de_perfil(nucleo):
    cache = perfilamiento.obtener_cache()
    cache.guardar(CLIENTE, {"puntaje": 720})
    cache.guardar(OTRO_CLIENTE, {"puntaje": 650})
    id_consentimiento = nucleo.post(RUTA, json=SOLICITUD).json()["id"]
    assert cache.obtener(CLIENTE) == {"puntaje": 720}

    nucleo.post(f"{RUTA}/{id_consentimiento}/revocacion")

    assert cache.obtener(CLIENTE) is None
    assert cache.obtener(OTRO_CLIENTE) == {"puntaje": 650}
    assert obtener_bus().suscriptores(EVENTO_REVOCADO) >= 1


def test_salud_reporta_que_perfilamiento_escucha_revocaciones(nucleo):
    salud = nucleo.get("/salud").json()

    assert salud["modulos"]["consentimiento"]["estado"] == "ok"
    assert salud["modulos"]["perfilamiento"]["estado"] == "ok"
    assert (
        "escucha revocaciones"
        in (salud["modulos"]["perfilamiento"]["dependencias"]["cache_perfiles"]["detalle"])
    )


# --- Criterio 6: todo queda en el log de auditoría con la versión del texto -----------------


def test_cada_evento_queda_en_auditoria_con_actor_canal_y_version_del_texto(
    servicio, almacen_auditoria
):
    consentimiento = otorgar(servicio, canal="movil")
    servicio.revocar(CLIENTE, consentimiento.id_consentimiento, canal="web")

    registros = almacen_auditoria.todos()
    assert [r.evento.tipo for r in registros] == [
        "consentimiento_otorgado",
        "consentimiento_revocado",
    ]
    otorgado, revocado = (r.evento for r in registros)
    assert otorgado.actor == CLIENTE
    assert otorgado.canal == "movil"
    assert revocado.canal == "web"
    assert otorgado.ocurrido_en == INSTANTE
    assert otorgado.variables_entrada["finalidad"] == "cotizacion"
    assert otorgado.version_texto_legal == "cotizacion-2026.1"
    assert otorgado.referencias["id_consentimiento"] == consentimiento.id_consentimiento
    assert revocado.resultado["estado"] == "revocado"


# --- Criterio 7: aviso a 30 días y renovación sin repetir el flujo --------------------------


def test_a_treinta_dias_del_vencimiento_se_avisa_y_se_puede_renovar(servicio, reloj, cliente):
    consentimiento = otorgar(servicio)
    reloj.avanzar((consentimiento.vence_en - INSTANTE - timedelta(days=20)).total_seconds())

    antes = cliente.get(f"{RUTA}/{consentimiento.id_consentimiento}").json()
    assert antes["estado"] == "vigente"
    assert antes["por_vencer"] is True
    assert antes["dias_para_vencer"] == 20

    renovado = cliente.post(f"{RUTA}/{consentimiento.id_consentimiento}/renovacion").json()
    ahora = reloj.ahora()
    assert renovado["estado"] == "vigente"
    assert renovado["por_vencer"] is False
    assert renovado["vence_en"] == sumar_meses(ahora, 12).isoformat().replace("+00:00", "Z")
    eventos = cliente.get(f"{RUTA}/{consentimiento.id_consentimiento}/eventos").json()
    assert [e["tipo"] for e in eventos] == ["otorgado", "renovado"]


def test_lejos_del_vencimiento_no_se_avisa(servicio, reloj):
    consentimiento = otorgar(servicio)
    reloj.avanzar(timedelta(days=100).total_seconds())

    assert consentimiento.por_vencer(reloj.ahora()) is False
    assert consentimiento.dias_para_vencer(reloj.ahora()) > 30


def test_un_consentimiento_revocado_no_se_renueva(servicio):
    consentimiento = otorgar(servicio)
    servicio.revocar(CLIENTE, consentimiento.id_consentimiento, canal="web")

    with pytest.raises(ConsentimientoRevocadoError):
        servicio.renovar(CLIENTE, consentimiento.id_consentimiento, canal="web")


def test_un_consentimiento_vencido_si_se_renueva_y_el_historico_lo_cuenta(servicio, reloj):
    consentimiento = otorgar(servicio)
    reloj.avanzar(timedelta(days=400).total_seconds())

    renovado = servicio.renovar(CLIENTE, consentimiento.id_consentimiento, canal="web")

    assert renovado.estado(reloj.ahora()) is Estado.VIGENTE
    assert [e.tipo for e in renovado.eventos] == [
        TipoEvento.OTORGADO,
        TipoEvento.VENCIDO,
        TipoEvento.RENOVADO,
    ]


# --- Criterio 8: histórico cronológico, incluidos revocados y vencidos ----------------------


def test_el_vencimiento_aparece_en_el_historico_con_su_fecha_y_canal_sistema(
    servicio, reloj, almacen_auditoria
):
    consentimiento = otorgar(servicio)
    reloj.avanzar(timedelta(days=400).total_seconds())

    listado = servicio.listar(CLIENTE)
    eventos = servicio.eventos(CLIENTE, consentimiento.id_consentimiento)

    assert listado[0].estado(reloj.ahora()) is Estado.VENCIDO
    assert [e.tipo for e in eventos] == [TipoEvento.OTORGADO, TipoEvento.VENCIDO]
    assert eventos[1].ocurrido_en == consentimiento.vence_en
    assert eventos[1].canal == "sistema"
    assert eventos[0].ocurrido_en < eventos[1].ocurrido_en
    # El vencimiento se audita una sola vez aunque se consulte muchas veces.
    servicio.listar(CLIENTE)
    assert [r.evento.tipo for r in almacen_auditoria.todos()] == [
        "consentimiento_otorgado",
        "consentimiento_vencido",
    ]


def test_el_listado_incluye_revocados_y_vigentes_en_orden(servicio, reloj):
    primero = otorgar(servicio)
    servicio.revocar(CLIENTE, primero.id_consentimiento, canal="web")
    reloj.avanzar(60)
    segundo = otorgar(servicio)

    listado = servicio.listar(CLIENTE)

    assert [c.id_consentimiento for c in listado] == [
        primero.id_consentimiento,
        segundo.id_consentimiento,
    ]
    assert [c.estado(reloj.ahora()) for c in listado] == [Estado.REVOCADO, Estado.VIGENTE]


def test_el_historico_de_un_consentimiento_ajeno_responde_404(cliente):
    id_consentimiento = cliente.post(RUTA, json=SOLICITUD).json()["id"]

    respuesta = cliente.get(
        f"/v1/consentimiento/clientes/{OTRO_CLIENTE}/consentimientos/{id_consentimiento}/eventos"
    )

    assert respuesta.status_code == 404
    assert respuesta.json()["title"] == "recurso_no_encontrado"


# --- Detalles del modelo --------------------------------------------------------------------


@pytest.mark.parametrize(
    ("fecha", "meses", "esperada"),
    [
        (datetime(2026, 1, 31, tzinfo=UTC), 1, datetime(2026, 2, 28, tzinfo=UTC)),
        (datetime(2026, 10, 17, tzinfo=UTC), 12, datetime(2027, 10, 17, tzinfo=UTC)),
        (datetime(2026, 8, 31, tzinfo=UTC), 6, datetime(2027, 2, 28, tzinfo=UTC)),
    ],
)
def test_sumar_meses_respeta_el_calendario(fecha, meses, esperada):
    assert sumar_meses(fecha, meses) == esperada


# --- Concurrencia: solicitudes simultáneas del mismo cliente --------------------------------

HILOS = 8


class RepositorioLento(RepositorioEnMemoria):
    """Pausa después de cada lectura: ensancha la ventana entre leer y guardar, para que una
    carrera no dependa de la suerte."""

    def obtener(self, id_consentimiento):
        leido = super().obtener(id_consentimiento)
        time.sleep(0.005)
        return leido

    def del_cliente(self, id_cliente):
        leidos = super().del_cliente(id_cliente)
        time.sleep(0.005)
        return leidos


@pytest.fixture
def servicio_lento(reloj, almacen_auditoria, bus) -> ServicioDeConsentimiento:
    return ServicioDeConsentimiento(
        repositorio=RepositorioLento(),
        auditoria=RegistroEnAuditoria(ServicioDeAuditoria(almacen_auditoria)),
        bus=bus,
        reloj=reloj,
    )


def a_la_vez(accion) -> list[object]:
    """Ejecuta `accion` en varios hilos que arrancan juntos; devuelve resultados o errores."""
    barrera = threading.Barrier(HILOS)
    resultados: list[object] = []

    def correr():
        barrera.wait()
        try:
            resultados.append(accion())
        except Exception as error:  # noqa: BLE001 - la prueba cuenta los errores
            resultados.append(error)

    hilos = [threading.Thread(target=correr) for _ in range(HILOS)]
    for hilo in hilos:
        hilo.start()
    for hilo in hilos:
        hilo.join()
    return resultados


def test_un_doble_otorgamiento_simultaneo_deja_un_solo_consentimiento_vigente(
    servicio_lento, reloj
):
    resultados = a_la_vez(lambda: otorgar(servicio_lento))

    otorgados = [r for r in resultados if not isinstance(r, Exception)]
    assert len(otorgados) == 1
    rechazados = [r for r in resultados if isinstance(r, Exception)]
    assert all(isinstance(r, ConsentimientoYaVigenteError) for r in rechazados)
    assert len(servicio_lento.listar(CLIENTE)) == 1

    # Y por eso revocar corta el acceso: no queda otro vigente que cotización encuentre.
    servicio_lento.revocar(CLIENTE, otorgados[0].id_consentimiento, canal="web")
    assert not servicio_lento.consentimiento_vigente(CLIENTE, "cotizacion").vigente


def test_una_revocacion_simultanea_queda_una_sola_vez_en_auditoria_y_en_el_bus(
    servicio_lento, almacen_auditoria, bus
):
    id_consentimiento = otorgar(servicio_lento).id_consentimiento

    resultados = a_la_vez(lambda: servicio_lento.revocar(CLIENTE, id_consentimiento, "web"))

    assert sum(not isinstance(r, Exception) for r in resultados) == 1
    assert all(
        isinstance(r, ConsentimientoNoVigenteError) for r in resultados if isinstance(r, Exception)
    )
    tipos = [r.evento.tipo for r in almacen_auditoria.todos()]
    assert tipos.count("consentimiento_revocado") == 1
    assert len(bus.publicados(EVENTO_REVOCADO)) == 1


def test_lecturas_simultaneas_registran_el_vencimiento_una_sola_vez(
    servicio_lento, reloj, almacen_auditoria
):
    consentimiento = otorgar(servicio_lento)
    reloj.avanzar(timedelta(days=400).total_seconds())

    a_la_vez(lambda: servicio_lento.obtener(CLIENTE, consentimiento.id_consentimiento))

    eventos = servicio_lento.eventos(CLIENTE, consentimiento.id_consentimiento)
    assert [e.tipo for e in eventos] == [TipoEvento.OTORGADO, TipoEvento.VENCIDO]
    tipos = [r.evento.tipo for r in almacen_auditoria.todos()]
    assert tipos.count("consentimiento_vencido") == 1


def test_no_se_renueva_un_vencido_si_ya_hay_otro_vigente_para_la_finalidad(servicio, reloj):
    viejo = otorgar(servicio)
    reloj.avanzar(timedelta(days=400).total_seconds())
    nuevo = otorgar(servicio)

    with pytest.raises(ConsentimientoYaVigenteError):
        servicio.renovar(CLIENTE, viejo.id_consentimiento, canal="web")
    assert servicio.consentimiento_vigente(CLIENTE, "cotizacion").id_consentimiento == (
        nuevo.id_consentimiento
    )


def test_la_composicion_crea_un_solo_servicio_aunque_lleguen_solicitudes_a_la_vez():
    creados: list[object] = []

    @instancia_unica
    def componentes() -> object:
        time.sleep(0.01)
        creados.append(object())
        return creados[-1]

    resultados = a_la_vez(componentes)

    assert len(creados) == 1
    assert all(r is creados[0] for r in resultados)
    componentes.cache_clear()
    assert componentes() is not creados[0]
