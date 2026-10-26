"""Consentimiento desde la app (MOB-F02): mismas reglas y mismo efecto que en la web.

El consentimiento es del cliente, no del canal. Estas pruebas recorren el núcleo completo:
lo que se otorga o revoca con `X-Canal: movil` se ve igual por la ruta que usa la web y rige
en la siguiente cotización (RC-04, P07).
"""

from __future__ import annotations

import itertools
from datetime import UTC, datetime, timedelta

import pytest
from fastapi.testclient import TestClient

from app.compartido.eventos import BusEnProceso, obtener_bus
from app.compartido.reloj import RelojFijo
from app.main import crear_app
from app.modulos.auditoria.api import dependencias as auditoria_dependencias
from app.modulos.auditoria.aplicacion.servicio import ServicioDeAuditoria
from app.modulos.auditoria.infraestructura.almacen_memoria import AlmacenEnMemoria
from app.modulos.consentimiento import EVENTO_REVOCADO
from app.modulos.consentimiento.api import dependencias
from app.modulos.consentimiento.aplicacion.servicio import ServicioDeConsentimiento
from app.modulos.consentimiento.dominio.errores import CanalInvalidoError
from app.modulos.consentimiento.dominio.modelo import Canal, TipoEvento
from app.modulos.consentimiento.infraestructura.auditoria_nucleo import RegistroEnAuditoria
from app.modulos.consentimiento.infraestructura.repositorio_memoria import RepositorioEnMemoria
from app.modulos.cotizacion.api import dependencias as cotizacion_dependencias
from app.modulos.perfilamiento.api import dependencias as perfilamiento_dependencias

CLIENTE = "CLI-2026-000318"
RUTA = f"/v1/consentimiento/clientes/{CLIENTE}/consentimientos"
MOVIL = {"X-Canal": "movil"}
SOLICITUD = {
    "finalidad": "cotizacion",
    "entidades_origen": ["ent-andes"],
    "version_texto": "cotizacion-2026.1",
}
INSTANTE = datetime(2026, 10, 26, 15, 0, tzinfo=UTC)


@pytest.fixture
def nucleo(monkeypatch) -> TestClient:
    monkeypatch.delenv(auditoria_dependencias.VARIABLE_DSN, raising=False)

    def reiniciar():
        auditoria_dependencias.reiniciar()
        dependencias.reiniciar()
        perfilamiento_dependencias.reiniciar()
        cotizacion_dependencias._componentes.cache_clear()
        obtener_bus.cache_clear()

    reiniciar()
    yield TestClient(crear_app())
    reiniciar()


_EDADES = itertools.count(30)


def _cotizar(nucleo) -> dict:
    """Cada llamada cambia la edad para que la idempotencia no devuelva la misma oferta."""
    return nucleo.post(
        "/v1/cotizacion/cotizaciones",
        json={
            "producto": "auto",
            "asegurado": {"edad": next(_EDADES), "ciudad": "Bogotá"},
            "id_cliente": CLIENTE,
        },
    ).json()


def test_lo_otorgado_en_la_app_se_ve_igual_por_la_ruta_de_la_web(nucleo):
    """Criterios 1 y 2: mismo estado, finalidad, alcance y vigencia en los dos canales."""
    otorgado = nucleo.post(RUTA, json=SOLICITUD, headers=MOVIL).json()

    (listado,) = nucleo.get(RUTA, headers={"X-Canal": "web"}).json()

    assert listado == otorgado
    assert listado["canal"] == "movil"
    assert listado["estado"] == "vigente"


def test_otorgar_desde_la_app_registra_el_canal_movil_en_el_historico(nucleo):
    """Criterio 3: queda registrado el canal `movil`."""
    otorgado = nucleo.post(RUTA, json=SOLICITUD, headers=MOVIL).json()

    eventos = nucleo.get(f"{RUTA}/{otorgado['id']}/eventos").json()

    assert [(e["tipo"], e["canal"]) for e in eventos] == [("otorgado", "movil")]


def test_revocar_desde_la_app_deja_la_siguiente_cotizacion_en_tarifa_estandar(nucleo):
    """Criterio 4: revocar en un canal corta el uso del dato en todos (RC-04)."""
    otorgado = nucleo.post(RUTA, json=SOLICITUD, headers={"X-Canal": "web"}).json()
    assert _cotizar(nucleo)["oferta"]["base_perfilamiento"] == "open_finance"

    revocado = nucleo.post(f"{RUTA}/{otorgado['id']}/revocacion", headers=MOVIL)

    assert revocado.status_code == 200
    siguiente = _cotizar(nucleo)
    assert siguiente["oferta"]["base_perfilamiento"] == "estandar"
    assert siguiente["oferta"]["uso_open_finance"] is False


def test_la_revocacion_desde_la_app_se_publica_con_su_canal(nucleo):
    """Perfilamiento vacía su caché al oír este evento: el canal viaja con él."""
    otorgado = nucleo.post(RUTA, json=SOLICITUD, headers={"X-Canal": "web"}).json()

    nucleo.post(f"{RUTA}/{otorgado['id']}/revocacion", headers=MOVIL)

    (evento,) = obtener_bus().publicados(EVENTO_REVOCADO)
    assert evento.carga["canal"] == "movil"
    assert evento.carga["id_consentimiento"] == otorgado["id"]


def test_un_canal_que_no_es_web_ni_movil_se_rechaza_en_la_cabecera(nucleo):
    respuesta = nucleo.post(RUTA, json=SOLICITUD, headers={"X-Canal": "sistema"})

    assert respuesta.status_code == 422


# -- reglas en el servicio, con el reloj controlado ---------------------------------------


@pytest.fixture
def reloj() -> RelojFijo:
    return RelojFijo(INSTANTE)


@pytest.fixture
def servicio(reloj) -> ServicioDeConsentimiento:
    return ServicioDeConsentimiento(
        repositorio=RepositorioEnMemoria(),
        auditoria=RegistroEnAuditoria(ServicioDeAuditoria(AlmacenEnMemoria())),
        bus=BusEnProceso(),
        reloj=reloj,
    )


def _otorgar(servicio, canal="movil"):
    return servicio.otorgar(id_cliente=CLIENTE, canal=canal, **SOLICITUD)


@pytest.mark.parametrize("canal", ["sistema", "fax", ""])
def test_solo_la_web_y_la_app_cambian_un_consentimiento(servicio, canal):
    """`sistema` es del núcleo (el vencimiento): un cliente nunca actúa como sistema."""
    with pytest.raises(CanalInvalidoError):
        _otorgar(servicio, canal)

    vigente = _otorgar(servicio, "web")
    with pytest.raises(CanalInvalidoError):
        servicio.revocar(CLIENTE, vigente.id_consentimiento, canal)
    with pytest.raises(CanalInvalidoError):
        servicio.renovar(CLIENTE, vigente.id_consentimiento, canal)


def test_por_vencer_se_renueva_desde_la_app_sin_repetir_el_flujo(servicio, reloj):
    """Criterio 5: a 30 días o menos se marca y se renueva con un solo paso."""
    otorgado = _otorgar(servicio, "web")
    reloj.avanzar((otorgado.vence_en - INSTANTE - timedelta(days=29)).total_seconds())
    assert servicio.obtener(CLIENTE, otorgado.id_consentimiento).por_vencer(reloj.ahora())

    renovado = servicio.renovar(CLIENTE, otorgado.id_consentimiento, Canal.MOVIL)

    assert renovado.vence_en > otorgado.vence_en
    assert [(e.tipo, e.canal) for e in renovado.eventos] == [
        (TipoEvento.OTORGADO, "web"),
        (TipoEvento.RENOVADO, "movil"),
    ]


def test_el_canal_es_un_texto_igual_al_que_ya_guardaba_el_modulo():
    """`Canal` es un StrEnum: los consentimientos guardados con "web" siguen siendo iguales."""
    assert Canal.MOVIL == "movil"
    assert str(Canal.SISTEMA) == "sistema"
