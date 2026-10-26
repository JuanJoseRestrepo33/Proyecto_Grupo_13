"""Pruebas de las rutas de consentimiento de los dos routers (MOB-F02, WEB-F02).

Cada prueba corre en la web y en la app: mismas rutas, mismas formas y mismos códigos de
error. Lo único que cambia es cómo viaja la sesión y el canal que se le pasa al núcleo.
"""

from __future__ import annotations

from dataclasses import dataclass

import pytest

from app.web.dependencias import COOKIE_SESION

from .conftest import CLIENTE, problema

NUCLEO = f"/v1/consentimiento/clientes/{CLIENTE}/consentimientos"
SOLICITUD = {
    "finalidad": "cotizacion",
    "entidades_origen": ["ent-andes"],
    "version_texto": "cotizacion-2026.1",
}
FINALIDAD = {
    "codigo": "cotizacion",
    "nombre": "Cotización personalizada",
    "descripcion": "Calcular una prima personalizada.",
    "alcance": ["Comportamiento de pago"],
    "vigencia_meses": 12,
    "entidades_origen": [{"id": "ent-andes", "nombre": "Banco de los Andes"}],
    "version_texto": "cotizacion-2026.1",
}
CONSENTIMIENTO = {
    "id": "CONSENT-1",
    "finalidad": "cotizacion",
    "entidades_origen": [{"id": "ent-andes", "nombre": "Banco de los Andes"}],
    "estado": "vigente",
    "canal": "movil",
    "otorgado_en": "2026-10-26T15:00:00Z",
    "vence_en": "2027-10-26T15:00:00Z",
    "revocado_en": None,
    "version_texto": "cotizacion-2026.1",
    "por_vencer": False,
    "dias_para_vencer": 365,
}
EVENTO = {
    "tipo": "otorgado",
    "ocurrido_en": "2026-10-26T15:00:00Z",
    "canal": "movil",
    "version_texto": "cotizacion-2026.1",
}


@dataclass
class Canal:
    nombre: str
    prefijo: str


CANALES = [Canal("web", "/api/v1/web"), Canal("movil", "/api/v1/movil")]


@pytest.fixture(params=CANALES, ids=lambda c: c.nombre)
def canal(request) -> Canal:
    return request.param


@pytest.fixture
def con_sesion(cliente, firmante, canal):
    token = firmante.emitir(canal=canal.nombre)
    if canal.nombre == "web":
        cliente.cookies.set(COOKIE_SESION, token)
    else:
        cliente.headers["Authorization"] = f"Bearer {token}"
    return cliente


def _al_nucleo(nucleo) -> list:
    return [s for s in nucleo.solicitudes if s.url.path != "/salud"]


@pytest.mark.parametrize(
    ("metodo", "ruta"),
    [
        ("GET", "/consentimientos/finalidades"),
        ("GET", "/consentimientos"),
        ("POST", "/consentimientos"),
        ("POST", "/consentimientos/humo-no-existe/revocacion"),
        ("POST", "/consentimientos/humo-no-existe/renovacion"),
        ("GET", "/consentimientos/humo-no-existe/eventos"),
    ],
)
def test_sin_sesion_toda_ruta_es_401_sin_tocar_el_nucleo(cliente, nucleo, canal, metodo, ruta):
    """Lo que espera el humo de `cd.yml` (#161): las rutas existen y exigen sesión."""
    respuesta = cliente.request(metodo, f"{canal.prefijo}{ruta}", json=SOLICITUD)

    assert respuesta.status_code == 401
    assert respuesta.json()["codigo"] == "sesion_no_vigente"
    assert _al_nucleo(nucleo) == []


def test_las_finalidades_llegan_del_nucleo(con_sesion, nucleo, canal):
    nucleo.responder("GET", "/v1/consentimiento/finalidades", 200, [FINALIDAD])

    respuesta = con_sesion.get(f"{canal.prefijo}/consentimientos/finalidades")

    assert respuesta.json() == [FINALIDAD]


def test_la_lista_es_la_del_cliente_de_la_sesion(con_sesion, nucleo, canal):
    nucleo.responder("GET", NUCLEO, 200, [CONSENTIMIENTO])

    respuesta = con_sesion.get(f"{canal.prefijo}/consentimientos")

    assert respuesta.json() == [CONSENTIMIENTO]
    assert nucleo.ultima.url.path == NUCLEO


def test_otorgar_pasa_el_canal_al_nucleo(con_sesion, nucleo, canal):
    """Criterio 3: queda registrado el canal por el que se otorgó."""
    nucleo.responder("POST", NUCLEO, 201, CONSENTIMIENTO)

    respuesta = con_sesion.post(f"{canal.prefijo}/consentimientos", json=SOLICITUD)

    assert respuesta.status_code == 201
    assert respuesta.json() == CONSENTIMIENTO
    assert nucleo.ultima.headers["X-Canal"] == canal.nombre
    assert nucleo.cuerpo_de(nucleo.ultima) == SOLICITUD


@pytest.mark.parametrize(
    ("cambio", "codigo"),
    [
        ({"finalidad": "marketing"}, "finalidad_invalida"),
        ({"entidades_origen": []}, "entidades_invalidas"),
    ],
)
def test_una_solicitud_invalida_no_llega_al_nucleo(con_sesion, nucleo, canal, cambio, codigo):
    respuesta = con_sesion.post(f"{canal.prefijo}/consentimientos", json={**SOLICITUD, **cambio})

    assert respuesta.status_code == 422
    assert respuesta.json()["codigo"] == codigo
    assert _al_nucleo(nucleo) == []


@pytest.mark.parametrize(
    ("codigo", "campo"),
    [
        ("entidades_invalidas", "entidades_origen"),
        ("version_texto_desactualizada", "version_texto"),
    ],
)
def test_los_400_de_datos_del_nucleo_son_422_con_su_campo(con_sesion, nucleo, canal, codigo, campo):
    nucleo.responder("POST", NUCLEO, problema(400, codigo, "no aplica"))

    respuesta = con_sesion.post(f"{canal.prefijo}/consentimientos", json=SOLICITUD)

    assert respuesta.status_code == 422
    assert respuesta.json()["codigo"] == codigo
    assert respuesta.json()["campos"][0]["campo"] == campo


def test_otorgar_otro_vigente_es_409(con_sesion, nucleo, canal):
    nucleo.responder("POST", NUCLEO, problema(409, "consentimiento_ya_vigente"))

    respuesta = con_sesion.post(f"{canal.prefijo}/consentimientos", json=SOLICITUD)

    assert (respuesta.status_code, respuesta.json()["codigo"]) == (409, "consentimiento_ya_vigente")


@pytest.mark.parametrize("accion", ["revocacion", "renovacion"])
def test_revocar_y_renovar_con_el_canal_de_la_sesion(con_sesion, nucleo, canal, accion):
    nucleo.responder("POST", f"{NUCLEO}/CONSENT-1/{accion}", 200, CONSENTIMIENTO)

    respuesta = con_sesion.post(f"{canal.prefijo}/consentimientos/CONSENT-1/{accion}")

    assert respuesta.status_code == 200
    assert nucleo.ultima.headers["X-Canal"] == canal.nombre


@pytest.mark.parametrize("accion", ["revocacion", "renovacion"])
def test_un_consentimiento_ajeno_o_inexistente_es_404_del_contrato(con_sesion, canal, accion):
    respuesta = con_sesion.post(f"{canal.prefijo}/consentimientos/CONSENT-OTRO/{accion}")

    assert respuesta.status_code == 404
    assert respuesta.json()["codigo"] == "consentimiento_no_encontrado"


@pytest.mark.parametrize("codigo", ["consentimiento_no_vigente", "consentimiento_revocado"])
def test_los_409_de_estado_llegan_con_su_codigo(con_sesion, nucleo, canal, codigo):
    nucleo.responder("POST", f"{NUCLEO}/CONSENT-1/revocacion", problema(409, codigo))

    respuesta = con_sesion.post(f"{canal.prefijo}/consentimientos/CONSENT-1/revocacion")

    assert (respuesta.status_code, respuesta.json()["codigo"]) == (409, codigo)


def test_el_historico_en_orden_cronologico(con_sesion, nucleo, canal):
    nucleo.responder("GET", f"{NUCLEO}/CONSENT-1/eventos", 200, [EVENTO])

    respuesta = con_sesion.get(f"{canal.prefijo}/consentimientos/CONSENT-1/eventos")

    assert respuesta.json() == [EVENTO]


def test_el_historico_de_uno_inexistente_es_404(con_sesion, canal):
    respuesta = con_sesion.get(f"{canal.prefijo}/consentimientos/CONSENT-OTRO/eventos")

    assert respuesta.json()["codigo"] == "consentimiento_no_encontrado"


@pytest.mark.parametrize("accion", ["revocacion", "renovacion"])
def test_un_identificador_inseguro_es_404_sin_tocar_el_nucleo(con_sesion, nucleo, canal, accion):
    respuesta = con_sesion.post(f"{canal.prefijo}/consentimientos/CONSENT..1/{accion}")

    assert respuesta.status_code == 404
    assert respuesta.json()["codigo"] == "consentimiento_no_encontrado"
    assert _al_nucleo(nucleo) == []
