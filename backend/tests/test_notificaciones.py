"""Pruebas de MOB-F06: el cambio de estado del siniestro llega como push a la app.

El disparador se prueba de punta a punta en el núcleo: se reporta un siniestro, la operación
interna cambia su estado y el proveedor simulado recibe el push con los datos para abrir el
detalle. Contra PostgreSQL, en `test_notificaciones_postgres.py`.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

import pytest
from fastapi.testclient import TestClient

from app.compartido.eventos import Evento
from app.compartido.reloj import RelojFijo
from app.main import crear_app
from app.modulos.notificaciones.api import dependencias
from app.modulos.notificaciones.aplicacion.servicio import (
    DispositivoNoEncontradoError,
    NotificacionNoEncontradaError,
    ServicioDeNotificaciones,
    TokenInvalidoError,
)
from app.modulos.notificaciones.dominio.modelo import (
    EstadoEntrega,
    id_de_dispositivo,
    texto_del_cambio,
)
from app.modulos.notificaciones.infraestructura.proveedor_simulado import ProveedorPushSimulado
from app.modulos.notificaciones.infraestructura.repositorio_memoria import (
    DispositivosEnMemoria,
    NotificacionesEnMemoria,
)
from app.modulos.polizas.api import dependencias as polizas_dependencias
from app.modulos.polizas.dominio.modelo import Poliza
from app.modulos.siniestros.api import dependencias as siniestros_dependencias
from app.modulos.siniestros.aplicacion.servicio import EVENTO_SINIESTRO_ESTADO_CAMBIADO

INSTANTE = datetime(2026, 11, 8, 15, 0, tzinfo=UTC)
CLIENTE = "CLI-2026-000318"
TOKEN = "fcm:token-del-telefono-de-prueba"


def _evento(id_evento="EVS-1", estado="en_evaluacion", id_cliente=CLIENTE) -> Evento:
    return Evento(
        nombre=EVENTO_SINIESTRO_ESTADO_CAMBIADO,
        carga={
            "id_siniestro": "SIN-1",
            "referencia": "SIN-2026-000001",
            "id_cliente": id_cliente,
            "estado_anterior": "reportado",
            "estado_nuevo": estado,
            "id_evento": id_evento,
            "requerimientos": 0,
        },
        ocurrido_en=INSTANTE,
    )


@pytest.fixture
def proveedor() -> ProveedorPushSimulado:
    return ProveedorPushSimulado()


@pytest.fixture
def servicio(proveedor) -> ServicioDeNotificaciones:
    return ServicioDeNotificaciones(
        DispositivosEnMemoria(), NotificacionesEnMemoria(), proveedor, RelojFijo(INSTANTE)
    )


# -- criterio 4: registro del dispositivo ------------------------------------------------


def test_registrar_es_idempotente_por_token(servicio):
    primero, nuevo = servicio.registrar_dispositivo(CLIENTE, TOKEN)
    otra_vez, nuevo_otra_vez = servicio.registrar_dispositivo(CLIENTE, f"  {TOKEN} ")

    assert nuevo and not nuevo_otra_vez
    assert primero.id_dispositivo == otra_vez.id_dispositivo == id_de_dispositivo(TOKEN)
    assert servicio.dispositivos(CLIENTE) == (otra_vez,)


def test_el_token_que_inicia_sesion_con_otra_cuenta_pasa_a_esa_cuenta(servicio, proveedor):
    servicio.registrar_dispositivo("CLI-ANTERIOR", TOKEN)

    servicio.registrar_dispositivo(CLIENTE, TOKEN)
    servicio.al_cambiar_estado(_evento(id_cliente="CLI-ANTERIOR"))

    assert servicio.dispositivos("CLI-ANTERIOR") == ()
    assert len(servicio.dispositivos(CLIENTE)) == 1
    assert proveedor.enviados == []


@pytest.mark.parametrize("token", ["", "   ", "con espacio", "x" * 4097])
def test_un_token_invalido_se_rechaza(servicio, token):
    with pytest.raises(TokenInvalidoError):
        servicio.registrar_dispositivo(CLIENTE, token)


def test_dar_de_baja_deja_de_enviar(servicio, proveedor):
    dispositivo, _ = servicio.registrar_dispositivo(CLIENTE, TOKEN)

    servicio.dar_de_baja(CLIENTE, dispositivo.id_dispositivo)
    notificacion = servicio.al_cambiar_estado(_evento())

    assert proveedor.enviados == []
    assert notificacion.estado is EstadoEntrega.SIN_DISPOSITIVO
    with pytest.raises(DispositivoNoEncontradoError):
        servicio.dar_de_baja(CLIENTE, dispositivo.id_dispositivo)


def test_no_se_da_de_baja_el_dispositivo_de_otro(servicio):
    dispositivo, _ = servicio.registrar_dispositivo(CLIENTE, TOKEN)

    with pytest.raises(DispositivoNoEncontradoError):
        servicio.dar_de_baja("CLI-OTRO", dispositivo.id_dispositivo)


# -- criterios 1 y 2: el push con los datos para abrir el detalle --------------------------


def test_el_cambio_llega_a_todos_los_dispositivos_del_cliente(servicio, proveedor):
    servicio.registrar_dispositivo(CLIENTE, TOKEN)
    servicio.registrar_dispositivo(CLIENTE, "fcm:la-tablet")

    notificacion = servicio.al_cambiar_estado(_evento(estado="requiere_informacion"))

    assert notificacion.estado is EstadoEntrega.ENVIADA
    assert notificacion.dispositivos == 2
    assert {m.token for m in proveedor.enviados} == {TOKEN, "fcm:la-tablet"}
    mensaje = proveedor.enviados[0]
    assert mensaje.titulo == "Tu siniestro SIN-2026-000001 cambió"
    assert mensaje.cuerpo == "Necesitamos un documento para seguir con tu caso."
    assert mensaje.datos == {
        "tipo": "siniestro_estado",
        "id_siniestro": "SIN-1",
        "referencia": "SIN-2026-000001",
        "estado": "requiere_informacion",
    }


def test_el_mismo_evento_dos_veces_manda_un_solo_push(servicio, proveedor):
    servicio.registrar_dispositivo(CLIENTE, TOKEN)

    primera = servicio.al_cambiar_estado(_evento())
    segunda = servicio.al_cambiar_estado(_evento())

    assert primera.id_notificacion == segunda.id_notificacion
    assert len(proveedor.enviados) == 1
    assert len(servicio.notificaciones(CLIENTE)) == 1


def test_con_el_proveedor_caido_queda_fallida_pero_visible(servicio, proveedor):
    """P20: sin push, la app igual ve la notificación y el estado en el seguimiento."""
    servicio.registrar_dispositivo(CLIENTE, TOKEN)
    proveedor.caido = True

    notificacion = servicio.al_cambiar_estado(_evento())

    assert notificacion.estado is EstadoEntrega.FALLIDA
    assert servicio.notificaciones(CLIENTE)[0].estado is EstadoEntrega.FALLIDA


def test_las_notificaciones_van_de_la_mas_reciente_y_se_marcan_leidas(servicio):
    servicio.al_cambiar_estado(_evento("EVS-1", "en_evaluacion"))
    servicio.al_cambiar_estado(_evento("EVS-2", "aprobado"))

    [reciente, anterior] = servicio.notificaciones(CLIENTE)
    servicio.marcar_leida(CLIENTE, anterior.id_notificacion)

    assert reciente.datos["estado"] == "aprobado"
    assert [n.leida for n in servicio.notificaciones(CLIENTE)] == [False, True]
    assert servicio.notificaciones(CLIENTE, limite=1) == (reciente,)
    with pytest.raises(NotificacionNoEncontradaError):
        servicio.marcar_leida("CLI-OTRO", reciente.id_notificacion)


@pytest.mark.parametrize(
    ("estado", "cuerpo"),
    [("pagado", "Ya pagamos tu indemnización."), ("desconocido", "Tu caso tiene novedades.")],
)
def test_el_texto_depende_del_estado(estado, cuerpo):
    assert texto_del_cambio("SIN-1", estado)[1] == cuerpo


def test_el_proveedor_se_configura_por_entorno():
    assert ProveedorPushSimulado.desde_entorno({"PUSH_MODO": "caido"}).caido
    assert not ProveedorPushSimulado.desde_entorno({}).caido
    with pytest.raises(RuntimeError):
        ProveedorPushSimulado.desde_entorno({"PUSH_MODO": "roto"})


# -- de punta a punta en el núcleo ---------------------------------------------------------

TOKEN_INTERNO = "token-interno-de-prueba"
SINIESTROS = f"/v1/siniestros/clientes/{CLIENTE}/siniestros"
NOTIFICACIONES = f"/v1/notificaciones/clientes/{CLIENTE}"


@pytest.fixture
def nucleo(monkeypatch) -> TestClient:
    monkeypatch.setenv(siniestros_dependencias.VARIABLE_TOKEN_INTERNO, TOKEN_INTERNO)
    for variable in (
        "SINIESTROS_DATABASE_URL",
        "AUDITORIA_DATABASE_URL",
        "POLIZAS_DATABASE_URL",
        "POLIZAS_SEMILLA",
        "NOTIFICACIONES_DATABASE_URL",
        "PUSH_MODO",
    ):
        monkeypatch.delenv(variable, raising=False)
    for modulo in (polizas_dependencias, siniestros_dependencias, dependencias):
        modulo.reiniciar()
    hoy = datetime.now(UTC).date()
    polizas_dependencias.obtener_servicio()._repositorio.guardar(
        Poliza("POL-1", CLIENTE, "auto", hoy - timedelta(days=30), hoy + timedelta(days=300))
    )
    yield TestClient(crear_app())
    for modulo in (polizas_dependencias, siniestros_dependencias, dependencias):
        modulo.reiniciar()


def test_cambiar_el_estado_manda_el_push_al_dispositivo_registrado(nucleo):
    registro = nucleo.post(f"{NOTIFICACIONES}/dispositivos", json={"token": TOKEN})
    assert registro.status_code == 201
    assert "token" not in registro.json()
    assert nucleo.post(f"{NOTIFICACIONES}/dispositivos", json={"token": TOKEN}).status_code == 200
    siniestro = nucleo.post(
        SINIESTROS,
        json={
            "numero_poliza": "POL-1",
            "tipo_evento": "hurto",
            "ocurrido_el": (datetime.now(UTC).date() - timedelta(days=1)).isoformat(),
            "descripcion": "Me robaron el celular en el bus.",
        },
    ).json()

    cambio = nucleo.post(
        f"/v1/siniestros/{siniestro['id']}/estado",
        json={"estado": "en_evaluacion"},
        headers={"X-Token-Interno": TOKEN_INTERNO},
    )

    assert cambio.status_code == 200
    [mensaje] = dependencias.obtener_proveedor().enviados
    assert mensaje.token == TOKEN
    assert mensaje.datos["id_siniestro"] == siniestro["id"]
    [notificacion] = nucleo.get(f"{NOTIFICACIONES}/notificaciones").json()
    assert notificacion["datos"]["referencia"] == siniestro["referencia"]
    assert notificacion["estado"] == "enviada" and notificacion["leida"] is False
    leida = nucleo.post(f"{NOTIFICACIONES}/notificaciones/{notificacion['id']}/lectura")
    assert leida.status_code == 204
    assert nucleo.get(f"{NOTIFICACIONES}/notificaciones").json()[0]["leida"] is True


def test_el_proveedor_caido_no_impide_cambiar_el_estado(nucleo, monkeypatch):
    nucleo.post(f"{NOTIFICACIONES}/dispositivos", json={"token": TOKEN})
    dependencias.obtener_proveedor().caido = True
    siniestro = nucleo.post(
        SINIESTROS,
        json={
            "numero_poliza": "POL-1",
            "tipo_evento": "hurto",
            "ocurrido_el": (datetime.now(UTC).date() - timedelta(days=1)).isoformat(),
            "descripcion": "Me robaron el celular en el bus.",
        },
    ).json()

    cambio = nucleo.post(
        f"/v1/siniestros/{siniestro['id']}/estado",
        json={"estado": "rechazado"},
        headers={"X-Token-Interno": TOKEN_INTERNO},
    )

    assert cambio.status_code == 200
    assert nucleo.get(f"{NOTIFICACIONES}/notificaciones").json()[0]["estado"] == "fallida"


def test_las_rutas_responden_sus_codigos(nucleo):
    assert nucleo.post(f"{NOTIFICACIONES}/dispositivos", json={"token": "a b"}).status_code == 422
    assert nucleo.delete(f"{NOTIFICACIONES}/dispositivos/DIS-NO").status_code == 404
    assert nucleo.post(f"{NOTIFICACIONES}/notificaciones/NOT-NO/lectura").status_code == 404
    dispositivo = nucleo.post(f"{NOTIFICACIONES}/dispositivos", json={"token": TOKEN}).json()
    assert nucleo.delete(f"{NOTIFICACIONES}/dispositivos/{dispositivo['id']}").status_code == 204


def test_salud_reporta_el_proveedor_de_push(nucleo):
    modulo = nucleo.get("/salud").json()["modulos"]["notificaciones"]["dependencias"]

    assert modulo["proveedor_push"]["estado"] == "simulado"
    assert modulo["repositorio_notificaciones"]["estado"] == "simulado"
