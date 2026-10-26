"""Pruebas del contrato HTTP de la fachada v1.

Prueban el contrato que consumen los BFF, no la implementación: si algo de esto cambia,
el cliente web de Harold y el móvil de Edwin se rompen.
"""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.compartido.reloj import RelojFijo
from app.main import crear_app
from app.modulos.cotizacion.api.dependencias import obtener_reloj, obtener_servicio
from app.modulos.cotizacion.aplicacion.servicio import ServicioDeCotizacion
from app.modulos.cotizacion.infraestructura.adaptadores_simulados import (
    ConsentimientoSimulado,
    PerfilamientoSimulado,
    RegistroDeDecisionesEnMemoria,
)
from app.modulos.cotizacion.infraestructura.repositorio_memoria import RepositorioEnMemoria

from .conftest import CLIENTE, CONSENTIMIENTO, INSTANTE_INICIAL

SOLICITUD = {
    "producto": "auto",
    "asegurado": {"edad": 34, "ciudad": "Bogotá", "valor_asegurado": 60000000},
}


@pytest.fixture
def entorno():
    """App con el reloj y el consentimiento bajo control de la prueba."""
    reloj = RelojFijo(INSTANTE_INICIAL)
    consentimiento = ConsentimientoSimulado()
    servicio = ServicioDeCotizacion(
        repositorio=RepositorioEnMemoria(),
        consentimiento=consentimiento,
        perfilamiento=PerfilamientoSimulado(),
        auditoria=RegistroDeDecisionesEnMemoria(),
        reloj=reloj,
    )
    app = crear_app()
    app.dependency_overrides[obtener_servicio] = lambda: servicio
    app.dependency_overrides[obtener_reloj] = lambda: reloj
    return TestClient(app), reloj, consentimiento


@pytest.fixture
def cliente(entorno):
    return entorno[0]


def test_salud_responde(cliente):
    cuerpo = cliente.get("/salud").json()

    assert cuerpo["estado"] == "ok"
    assert cuerpo["modulos"]["cotizacion"]["estado"] == "ok"


def test_el_indice_de_la_fachada_declara_sus_capacidades(cliente):
    """El despliegue continuo usa esta ruta como prueba de humo."""
    cuerpo = cliente.get("/v1/").json()

    assert cuerpo["version"] == "v1"
    assert "cotizacion" in cuerpo["capacidades"]


def test_el_catalogo_web_no_incluye_vida_hipotecario(cliente):
    productos = cliente.get("/v1/cotizacion/productos").json()

    ids = {p["id"] for p in productos}
    assert ids == {"auto", "hogar", "viaje", "dispositivos"}
    assert all(p["prima_estandar"] > 0 for p in productos)


def test_crear_cotizacion_devuelve_la_oferta_completa(cliente):
    respuesta = cliente.post("/v1/cotizacion/cotizaciones", json=SOLICITUD)

    assert respuesta.status_code == 201
    cuerpo = respuesta.json()
    assert cuerpo["id_cotizacion"].startswith("QT-2026-")
    assert cuerpo["estado"] == "vigente"
    assert cuerpo["segundos_restantes"] == 300
    assert cuerpo["oferta"]["moneda"] == "COP"
    assert cuerpo["oferta"]["prima_mensual"] == 148000
    assert cuerpo["oferta"]["ahorro_mensual"] == 0
    assert cuerpo["oferta"]["uso_open_finance"] is False
    assert len(cuerpo["oferta"]["coberturas"]) == 3


def test_con_consentimiento_la_oferta_trae_ahorro(entorno):
    cliente, _, consentimiento = entorno
    consentimiento.otorgar(CLIENTE, CONSENTIMIENTO)

    cuerpo = cliente.post(
        "/v1/cotizacion/cotizaciones", json={**SOLICITUD, "id_cliente": CLIENTE}
    ).json()

    assert cuerpo["oferta"]["uso_open_finance"] is True
    assert cuerpo["oferta"]["prima_mensual"] == 133200
    assert cuerpo["oferta"]["ahorro_mensual"] == 14800
    assert cuerpo["id_consentimiento"] == CONSENTIMIENTO


def test_la_explicacion_del_precio_lista_los_factores(cliente):
    id_cotizacion = cliente.post("/v1/cotizacion/cotizaciones", json=SOLICITUD).json()[
        "id_cotizacion"
    ]

    cuerpo = cliente.get(f"/v1/cotizacion/cotizaciones/{id_cotizacion}/explicacion").json()

    assert cuerpo["version_regla"] == "rating-v3.2"
    assert len(cuerpo["factores"]) >= 3
    assert all("efecto_porcentual" in f for f in cuerpo["factores"])


def test_consultar_una_oferta_vencida_la_reporta_vencida(entorno):
    cliente, reloj, _ = entorno
    id_cotizacion = cliente.post("/v1/cotizacion/cotizaciones", json=SOLICITUD).json()[
        "id_cotizacion"
    ]

    reloj.avanzar(301)
    cuerpo = cliente.get(f"/v1/cotizacion/cotizaciones/{id_cotizacion}").json()

    assert cuerpo["estado"] == "vencida"
    assert cuerpo["segundos_restantes"] == 0


def test_recotizar_devuelve_una_cotizacion_nueva(entorno):
    cliente, reloj, _ = entorno
    original = cliente.post("/v1/cotizacion/cotizaciones", json=SOLICITUD).json()
    reloj.avanzar(301)

    respuesta = cliente.post(f"/v1/cotizacion/cotizaciones/{original['id_cotizacion']}/recotizar")

    assert respuesta.status_code == 201
    cuerpo = respuesta.json()
    assert cuerpo["id_cotizacion"] != original["id_cotizacion"]
    assert cuerpo["id_cotizacion_origen"] == original["id_cotizacion"]
    assert cuerpo["estado"] == "vigente"


def test_una_cotizacion_inexistente_responde_problem_json(cliente):
    respuesta = cliente.get("/v1/cotizacion/cotizaciones/QT-2026-99999")

    assert respuesta.status_code == 404
    assert respuesta.headers["content-type"].startswith("application/problem+json")
    cuerpo = respuesta.json()
    assert cuerpo["title"] == "recurso_no_encontrado"
    assert cuerpo["status"] == 404


def test_una_edad_fuera_de_rango_se_rechaza_en_el_contrato(cliente):
    respuesta = cliente.post(
        "/v1/cotizacion/cotizaciones",
        json={**SOLICITUD, "asegurado": {"edad": 7, "ciudad": "Bogotá"}},
    )

    assert respuesta.status_code == 422


def test_un_producto_desconocido_se_rechaza(cliente):
    respuesta = cliente.post(
        "/v1/cotizacion/cotizaciones", json={**SOLICITUD, "producto": "mascotas"}
    )

    assert respuesta.status_code == 422


def test_el_contrato_no_acepta_campos_desconocidos(cliente):
    """`extra="forbid"`: un campo de más es un error del cliente, no se ignora."""
    respuesta = cliente.post(
        "/v1/cotizacion/cotizaciones", json={**SOLICITUD, "descuento_manual": 50000}
    )

    assert respuesta.status_code == 422


def test_la_cotizacion_dice_su_dueno_y_su_motivo(entorno):
    cliente, _, _ = entorno

    cuerpo = cliente.post(
        "/v1/cotizacion/cotizaciones", json={**SOLICITUD, "id_cliente": CLIENTE}
    ).json()

    assert cuerpo["id_cliente"] == CLIENTE
    assert cuerpo["motivo_tarifa"] == "sin_consentimiento"


def test_cotizar_sin_open_finance_responde_el_motivo(entorno):
    cliente, _, consentimiento = entorno
    consentimiento.otorgar(CLIENTE, CONSENTIMIENTO)

    cuerpo = cliente.post(
        "/v1/cotizacion/cotizaciones",
        json={**SOLICITUD, "id_cliente": CLIENTE, "usar_open_finance": False},
        headers={"X-Canal": "web"},
    ).json()

    assert cuerpo["oferta"]["uso_open_finance"] is False
    assert cuerpo["motivo_tarifa"] == "open_finance_desactivado"


def test_un_canal_desconocido_se_rechaza(cliente):
    respuesta = cliente.post(
        "/v1/cotizacion/cotizaciones", json=SOLICITUD, headers={"X-Canal": "fax"}
    )

    assert respuesta.status_code == 422


def test_sin_base_la_salud_reporta_el_repositorio_caido(monkeypatch):
    """Con la variable definida y la base inalcanzable no se cae a memoria en silencio."""
    from app.modulos.cotizacion.api import dependencias

    monkeypatch.setenv(dependencias.VARIABLE_DSN, "postgresql://nadie@127.0.0.1:9/solventa")
    dependencias.reiniciar()
    try:
        estado = {d.nombre: d for d in dependencias.estado_dependencias()}
    finally:
        monkeypatch.delenv(dependencias.VARIABLE_DSN)
        dependencias.reiniciar()

    assert estado["repositorio_cotizaciones"].estado == "caido"
