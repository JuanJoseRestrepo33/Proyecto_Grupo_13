"""Pruebas de WEB-F06: estado, historial y requerimientos del siniestro.

El cambio de estado es la operación interna del núcleo; el cliente ve el resultado en el
detalle, igual en los dos canales.
"""

from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta

import pytest
from fastapi.testclient import TestClient

from app.compartido.errores import DatoInvalido
from app.compartido.eventos import BusEnProceso
from app.compartido.reloj import RelojFijo
from app.main import crear_app
from app.modulos.auditoria.infraestructura.almacen_memoria import AlmacenEnMemoria
from app.modulos.polizas.api import dependencias as polizas_dependencias
from app.modulos.siniestros.api import dependencias
from app.modulos.siniestros.aplicacion.servicio import EVENTO_SINIESTRO_ESTADO_CAMBIADO
from app.modulos.siniestros.dominio.errores import SiniestroCerradoError, SiniestroNoEncontradoError
from app.modulos.siniestros.dominio.modelo import EstadoSiniestro, SiguientePaso
from app.modulos.siniestros.dominio.seguimiento import (
    TRANSICIONES,
    CambioConcurrenteError,
    RequerimientoNoEncontradoError,
    RequerimientosObligatoriosError,
    RequerimientoYaAtendidoError,
    TransicionNoPermitidaError,
)

from .test_siniestros import CLIENTE, DATOS, INSTANTE, VENCIDA, VIGENTE, PolizasFalsas, _servicio

E = EstadoSiniestro
FOTO = b"\xff\xd8\xff\xe0" + b"foto" * 100


@pytest.fixture
def reloj() -> RelojFijo:
    return RelojFijo(INSTANTE)


@pytest.fixture
def almacen() -> AlmacenEnMemoria:
    return AlmacenEnMemoria()


@pytest.fixture
def bus() -> BusEnProceso:
    return BusEnProceso()


@pytest.fixture
def servicio(reloj, almacen, bus):
    return _servicio(PolizasFalsas(VIGENTE, VENCIDA), reloj, almacen, bus)


@pytest.fixture
def reportado(servicio):
    return servicio.reportar(CLIENTE, DATOS, "movil", "clave-0001")[0]


def _adjuntar(servicio, siniestro, **opciones):
    return servicio.adjuntar_evidencia(
        CLIENTE, siniestro.id_siniestro, "image/jpeg", FOTO, "factura.jpg", "movil", **opciones
    )


# -- criterios 1 y 2: el estado cambia y se ve ---------------------------------------------


def test_cambiar_el_estado_lo_deja_visible_para_el_cliente(servicio, reportado, reloj):
    reloj.avanzar(3600)

    actualizado = servicio.cambiar_estado(reportado.id_siniestro, E.EN_EVALUACION, "Ajustador")

    assert actualizado.estado is E.EN_EVALUACION
    visto = servicio.obtener(CLIENTE, reportado.id_siniestro)
    assert visto.estado is E.EN_EVALUACION
    assert visto.estado_actualizado_en == reloj.ahora()
    assert visto.siguiente_paso is SiguientePaso.ESPERAR_EVALUACION
    assert servicio.del_cliente(CLIENTE)[0].estado is E.EN_EVALUACION


@pytest.mark.parametrize(
    "camino",
    [
        [E.EN_EVALUACION, E.APROBADO, E.PAGADO],
        [E.EN_EVALUACION, E.RECHAZADO],
        [E.RECHAZADO],
        [E.EN_EVALUACION, E.REQUIERE_INFORMACION, E.EN_EVALUACION, E.APROBADO],
    ],
)
def test_los_caminos_validos_llegan_a_su_estado(servicio, reportado, camino):
    for estado in camino:
        requerimientos = ["Factura"] if estado is E.REQUIERE_INFORMACION else []
        servicio.cambiar_estado(reportado.id_siniestro, estado, requerimientos=requerimientos)

    assert servicio.obtener(CLIENTE, reportado.id_siniestro).estado is camino[-1]


@pytest.mark.parametrize(
    ("desde", "hacia"),
    [(E.REPORTADO, E.PAGADO), (E.REPORTADO, E.APROBADO), (E.REPORTADO, E.REPORTADO)],
)
def test_una_transicion_que_no_existe_se_rechaza(servicio, reportado, desde, hacia):
    assert reportado.estado is desde
    with pytest.raises(TransicionNoPermitidaError) as error:
        servicio.cambiar_estado(reportado.id_siniestro, hacia)

    assert error.value.detalles["permitidos"] == sorted(str(e) for e in TRANSICIONES[desde])


def test_los_estados_finales_no_admiten_cambios():
    assert TRANSICIONES[E.RECHAZADO] == TRANSICIONES[E.PAGADO] == frozenset()


def test_un_siniestro_inexistente_no_cambia(servicio):
    with pytest.raises(SiniestroNoEncontradoError):
        servicio.cambiar_estado("SIN-NO-EXISTE", E.EN_EVALUACION)


def test_dos_cambios_simultaneos_dejan_uno(servicio, reportado):
    def cambiar(_):
        try:
            return servicio.cambiar_estado(reportado.id_siniestro, E.EN_EVALUACION)
        except (CambioConcurrenteError, TransicionNoPermitidaError):
            return None

    with ThreadPoolExecutor(8) as hilos:
        resultados = list(hilos.map(cambiar, range(8)))

    assert len([r for r in resultados if r is not None]) == 1
    assert len(servicio.historial(CLIENTE, reportado.id_siniestro)) == 2


def test_un_cambio_que_pierde_la_carrera_es_concurrente(servicio, reportado, monkeypatch):
    monkeypatch.setattr(servicio._repositorio, "cambiar_estado", lambda *_: False)

    with pytest.raises(CambioConcurrenteError):
        servicio.cambiar_estado(reportado.id_siniestro, E.EN_EVALUACION)


# -- criterio 3: auditoría y evento ---------------------------------------------------------


def test_el_cambio_queda_auditado_y_se_publica(servicio, reportado, almacen, bus):
    servicio.cambiar_estado(reportado.id_siniestro, E.EN_EVALUACION, "Revisión inicial")

    eventos = bus.publicados(EVENTO_SINIESTRO_ESTADO_CAMBIADO)
    assert len(eventos) == 1
    assert eventos[0].carga == {
        "id_siniestro": reportado.id_siniestro,
        "referencia": reportado.referencia,
        "id_cliente": CLIENTE,
        "estado_anterior": "reportado",
        "estado_nuevo": "en_evaluacion",
        "id_evento": eventos[0].carga["id_evento"],
        "requerimientos": 0,
    }
    historial = servicio.historial(CLIENTE, reportado.id_siniestro)
    registro = [r for r in almacen.todos() if r.evento.tipo == "siniestro_estado_cambiado"]
    assert len(registro) == 1
    assert registro[0].id_decision == historial[-1].id_decision
    assert registro[0].evento.resultado["estado"] == "en_evaluacion"
    assert registro[0].evento.variables_entrada["motivo"] == "Revisión inicial"


# -- criterio 4: historial con fechas -------------------------------------------------------


def test_el_historial_empieza_por_el_reporte_y_trae_cada_cambio(servicio, reportado, reloj):
    reloj.avanzar(60)
    servicio.cambiar_estado(reportado.id_siniestro, E.EN_EVALUACION)
    reloj.avanzar(60)
    servicio.cambiar_estado(reportado.id_siniestro, E.APROBADO, "Cumple la cobertura")

    historial = servicio.historial(CLIENTE, reportado.id_siniestro)

    assert [(e.estado_anterior, e.estado_nuevo) for e in historial] == [
        (None, E.REPORTADO),
        (E.REPORTADO, E.EN_EVALUACION),
        (E.EN_EVALUACION, E.APROBADO),
    ]
    assert historial[0].ocurrido_en == reportado.reportado_en
    assert historial[2].ocurrido_en == reportado.reportado_en + timedelta(seconds=120)
    assert historial[2].motivo == "Cumple la cobertura"


def test_el_historial_de_otro_cliente_no_existe(servicio, reportado):
    with pytest.raises(SiniestroNoEncontradoError):
        servicio.historial("CLI-OTRO", reportado.id_siniestro)


# -- criterio 5: requerimientos ------------------------------------------------------------


def test_pedir_informacion_deja_requerimientos_pendientes(servicio, reportado):
    servicio.cambiar_estado(
        reportado.id_siniestro,
        E.REQUIERE_INFORMACION,
        requerimientos=["  Factura de la reparación ", "", "Foto del vehículo"],
    )

    requerimientos = servicio.requerimientos(CLIENTE, reportado.id_siniestro)

    assert [r.descripcion for r in requerimientos] == [
        "Factura de la reparación",
        "Foto del vehículo",
    ]
    assert all(r.pendiente for r in requerimientos)
    visto = servicio.obtener(CLIENTE, reportado.id_siniestro)
    assert visto.siguiente_paso is SiguientePaso.ADJUNTAR_EVIDENCIA


def test_pedir_informacion_sin_decir_que_falta_se_rechaza(servicio, reportado):
    with pytest.raises(RequerimientosObligatoriosError):
        servicio.cambiar_estado(
            reportado.id_siniestro, E.REQUIERE_INFORMACION, requerimientos=[" "]
        )


def test_demasiados_requerimientos_o_muy_largos_se_rechazan(servicio, reportado):
    with pytest.raises(DatoInvalido):
        servicio.cambiar_estado(
            reportado.id_siniestro, E.REQUIERE_INFORMACION, requerimientos=["x"] * 11
        )
    with pytest.raises(DatoInvalido):
        servicio.cambiar_estado(
            reportado.id_siniestro, E.REQUIERE_INFORMACION, requerimientos=["x" * 301]
        )


def test_adjuntar_evidencia_responde_el_requerimiento(servicio, reportado):
    servicio.cambiar_estado(
        reportado.id_siniestro, E.REQUIERE_INFORMACION, requerimientos=["Factura"]
    )
    pedido = servicio.requerimientos(CLIENTE, reportado.id_siniestro)[0]

    evidencia = _adjuntar(servicio, reportado, id_requerimiento=pedido.id_requerimiento)

    atendido = servicio.requerimientos(CLIENTE, reportado.id_siniestro)[0]
    assert not atendido.pendiente
    assert atendido.id_evidencia == evidencia.id_evidencia
    with pytest.raises(RequerimientoYaAtendidoError):
        _adjuntar(servicio, reportado, id_requerimiento=pedido.id_requerimiento)


def test_un_requerimiento_inexistente_no_guarda_la_evidencia(servicio, reportado):
    with pytest.raises(RequerimientoNoEncontradoError):
        _adjuntar(servicio, reportado, id_requerimiento="REQ-NO-EXISTE")

    assert servicio.evidencias(CLIENTE, reportado.id_siniestro) == ()


def test_un_siniestro_cerrado_ya_no_recibe_evidencia(servicio, reportado):
    servicio.cambiar_estado(reportado.id_siniestro, E.RECHAZADO, "Fuera de cobertura")

    with pytest.raises(SiniestroCerradoError):
        _adjuntar(servicio, reportado)


# -- contrato HTTP ---------------------------------------------------------------------------

RUTA = f"/v1/siniestros/clientes/{CLIENTE}/siniestros"
TOKEN = "token-interno-de-prueba"


@pytest.fixture
def nucleo(monkeypatch) -> TestClient:
    monkeypatch.setenv(dependencias.VARIABLE_TOKEN_INTERNO, TOKEN)
    for variable in ("SINIESTROS_DATABASE_URL", "AUDITORIA_DATABASE_URL", "POLIZAS_DATABASE_URL"):
        monkeypatch.delenv(variable, raising=False)
    monkeypatch.delenv(dependencias.VARIABLE_ALMACENAMIENTO, raising=False)
    monkeypatch.delenv(polizas_dependencias.VARIABLE_SEMILLA, raising=False)
    polizas_dependencias.reiniciar()
    dependencias.reiniciar()
    from app.modulos.polizas.dominio.modelo import Poliza

    hoy = DATOS.ocurrido_el
    polizas_dependencias.obtener_servicio()._repositorio.guardar(
        Poliza("POL-VIGENTE", CLIENTE, "auto", hoy - timedelta(days=30), hoy + timedelta(days=300))
    )
    yield TestClient(crear_app())
    dependencias.reiniciar()
    polizas_dependencias.reiniciar()


def _reportar(nucleo) -> dict:
    respuesta = nucleo.post(
        RUTA,
        json={
            "numero_poliza": "POL-VIGENTE",
            "tipo_evento": "accidente_vehiculo",
            "ocurrido_el": DATOS.ocurrido_el.isoformat(),
            "descripcion": "Choque leve en la calle 80 al frenar.",
        },
        headers={"X-Canal": "movil"},
    )
    assert respuesta.status_code == 201, respuesta.text
    return respuesta.json()


def _cambiar(nucleo, id_siniestro, cuerpo, token=TOKEN):
    encabezados = {"X-Token-Interno": token} if token else {}
    return nucleo.post(f"/v1/siniestros/{id_siniestro}/estado", json=cuerpo, headers=encabezados)


def test_el_cambio_interno_se_ve_en_el_detalle_con_historial_y_requerimientos(nucleo):
    creado = _reportar(nucleo)

    cambiado = _cambiar(
        nucleo,
        creado["id"],
        {
            "estado": "requiere_informacion",
            "motivo": "Falta soporte",
            "requerimientos": [{"descripcion": "Factura de la reparación"}],
        },
    )

    assert cambiado.status_code == 200, cambiado.text
    assert cambiado.json()["estado"] == "requiere_informacion"
    detalle = nucleo.get(f"{RUTA}/{creado['id']}").json()
    assert detalle["estado"] == "requiere_informacion"
    assert detalle["siguiente_paso"] == "adjuntar_evidencia"
    assert detalle["estado_actualizado_en"] is not None
    assert [(e["estado_anterior"], e["estado"]) for e in detalle["historial"]] == [
        (None, "reportado"),
        ("reportado", "requiere_informacion"),
    ]
    assert detalle["historial"][1]["motivo"] == "Falta soporte"
    [requerimiento] = detalle["requerimientos"]
    assert requerimiento["descripcion"] == "Factura de la reparación"
    assert requerimiento["pendiente"] is True

    respuesta = nucleo.post(
        f"{RUTA}/{creado['id']}/evidencias",
        content=FOTO,
        headers={"Content-Type": "image/jpeg", "X-Canal": "movil"},
        params={"id_requerimiento": requerimiento["id"]},
    )
    assert respuesta.status_code == 201
    detalle = nucleo.get(f"{RUTA}/{creado['id']}").json()
    assert detalle["requerimientos"][0]["pendiente"] is False
    assert detalle["requerimientos"][0]["id_evidencia"] == respuesta.json()["id"]
    assert [s["estado"] for s in nucleo.get(RUTA).json()] == ["requiere_informacion"]


@pytest.mark.parametrize(
    ("cuerpo", "estado", "codigo"),
    [
        ({"estado": "pagado"}, 409, "transicion_no_permitida"),
        ({"estado": "requiere_informacion"}, 422, "requerimientos_obligatorios"),
    ],
)
def test_un_cambio_invalido_responde_su_codigo(nucleo, cuerpo, estado, codigo):
    creado = _reportar(nucleo)

    respuesta = _cambiar(nucleo, creado["id"], cuerpo)

    assert respuesta.status_code == estado
    assert respuesta.json()["title"] == codigo


def test_sin_el_token_interno_correcto_es_401(nucleo):
    creado = _reportar(nucleo)

    sin_token = _cambiar(nucleo, creado["id"], {"estado": "en_evaluacion"}, token=None)
    otro = _cambiar(nucleo, creado["id"], {"estado": "en_evaluacion"}, token="otro")

    assert sin_token.status_code == otro.status_code == 401
    assert nucleo.get(f"{RUTA}/{creado['id']}").json()["estado"] == "reportado"


def test_sin_la_variable_la_operacion_interna_no_existe(nucleo, monkeypatch):
    creado = _reportar(nucleo)
    monkeypatch.delenv(dependencias.VARIABLE_TOKEN_INTERNO)

    respuesta = _cambiar(nucleo, creado["id"], {"estado": "en_evaluacion"})

    assert respuesta.status_code == 404


def test_un_requerimiento_que_no_existe_es_404(nucleo):
    creado = _reportar(nucleo)

    respuesta = nucleo.post(
        f"{RUTA}/{creado['id']}/evidencias",
        content=FOTO,
        headers={"Content-Type": "image/jpeg"},
        params={"id_requerimiento": "REQ-NO"},
    )

    assert respuesta.status_code == 404
    assert respuesta.json()["title"] == "requerimiento_no_encontrado"
