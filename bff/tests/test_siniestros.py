"""Pruebas de las rutas de siniestros de los dos routers (WEB-F05).

Cada prueba corre en la web y en la app: el reporte es el mismo en los dos canales.
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass

import pytest

from app.web.dependencias import COOKIE_SESION

from .conftest import CLIENTE, problema

NUCLEO = f"/v1/siniestros/clientes/{CLIENTE}/siniestros"
CLAVE = str(uuid.uuid4())
SOLICITUD = {
    "numero_poliza": "POL-2026-000101-000318",
    "tipo_evento": "accidente_vehiculo",
    "ocurrido_el": "2026-10-25",
    "descripcion": "Choque leve en la calle 80 al frenar.",
    "monto_estimado": {"valor": 1500000, "moneda": "COP"},
}
DEL_NUCLEO = {
    "id": "SIN-1",
    "referencia": "SIN-2026-000001",
    "numero_poliza": "POL-2026-000101-000318",
    "tipo_evento": "accidente_vehiculo",
    "ocurrido_el": "2026-10-25",
    "descripcion": "Choque leve en la calle 80 al frenar.",
    "monto_estimado": 1500000,
    "estado": "reportado",
    "siguiente_paso": "adjuntar_evidencia",
    "canal": "movil",
    "reportado_en": "2026-10-26T15:00:00Z",
}
PARA_EL_CANAL = {**DEL_NUCLEO, "monto_estimado": {"valor": 1500000, "moneda": "COP"}}
POLIZA = {
    "numero": "POL-2026-000101-000318",
    "producto": "auto",
    "inicio": "2026-09-26",
    "fin": "2027-09-26",
    "estado": "vigente",
}


@dataclass
class Canal:
    nombre: str
    prefijo: str


@pytest.fixture(
    params=[Canal("web", "/api/v1/web"), Canal("movil", "/api/v1/movil")], ids=lambda c: c.nombre
)
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
        ("GET", "/polizas"),
        ("GET", "/siniestros"),
        ("POST", "/siniestros"),
        ("GET", "/siniestros/x"),
    ],
)
def test_sin_sesion_toda_ruta_es_401(cliente, nucleo, canal, metodo, ruta):
    respuesta = cliente.request(metodo, f"{canal.prefijo}{ruta}", json=SOLICITUD)

    assert respuesta.status_code == 401
    assert _al_nucleo(nucleo) == []


def test_las_polizas_del_cliente_para_el_asistente(con_sesion, nucleo, canal):
    nucleo.responder("GET", f"/v1/polizas/clientes/{CLIENTE}/polizas", 200, [POLIZA])

    assert con_sesion.get(f"{canal.prefijo}/polizas").json() == [POLIZA]


def test_reportar_responde_201_con_la_referencia(con_sesion, nucleo, canal):
    """Criterio 1: referencia, estado y siguiente paso, con el canal que reportó."""
    nucleo.responder("POST", NUCLEO, 201, DEL_NUCLEO)

    respuesta = con_sesion.post(
        f"{canal.prefijo}/siniestros", json=SOLICITUD, headers={"Idempotency-Key": CLAVE}
    )

    assert respuesta.status_code == 201
    assert respuesta.json() == PARA_EL_CANAL
    enviado = nucleo.ultima
    assert enviado.headers["X-Canal"] == canal.nombre
    assert enviado.headers["Idempotency-Key"] == CLAVE
    assert nucleo.cuerpo_de(enviado)["monto_estimado"] == 1500000


def test_sin_monto_estimado_se_reporta_igual(con_sesion, nucleo, canal):
    nucleo.responder("POST", NUCLEO, 201, {**DEL_NUCLEO, "monto_estimado": None})
    sin_monto = {k: v for k, v in SOLICITUD.items() if k != "monto_estimado"}

    respuesta = con_sesion.post(
        f"{canal.prefijo}/siniestros", json=sin_monto, headers={"Idempotency-Key": CLAVE}
    )

    assert respuesta.json()["monto_estimado"] is None
    assert nucleo.cuerpo_de(nucleo.ultima)["monto_estimado"] is None


def test_el_reintento_con_la_misma_clave_responde_200(con_sesion, nucleo, canal):
    """Criterio 4: el doble envío devuelve el mismo siniestro, sin crear otro."""
    nucleo.responder("POST", NUCLEO, 200, DEL_NUCLEO)

    respuesta = con_sesion.post(
        f"{canal.prefijo}/siniestros", json=SOLICITUD, headers={"Idempotency-Key": CLAVE}
    )

    assert respuesta.status_code == 200
    assert respuesta.json()["referencia"] == "SIN-2026-000001"


@pytest.mark.parametrize("clave", [None, "no-es-uuid"])
def test_sin_clave_de_idempotencia_valida_es_422_sin_tocar_el_nucleo(
    con_sesion, nucleo, canal, clave
):
    encabezados = {"Idempotency-Key": clave} if clave else {}

    respuesta = con_sesion.post(f"{canal.prefijo}/siniestros", json=SOLICITUD, headers=encabezados)

    assert respuesta.status_code == 422
    assert respuesta.json()["codigo"] == "clave_idempotencia_invalida"
    assert _al_nucleo(nucleo) == []


@pytest.mark.parametrize(
    "cambio",
    [{"descripcion": "corta"}, {"tipo_evento": "otro_tipo"}, {"ocurrido_el": "ayer"}],
)
def test_datos_invalidos_no_llegan_al_nucleo(con_sesion, nucleo, canal, cambio):
    """Criterio 2: el asistente no avanza sin póliza, tipo, fecha y descripción."""
    respuesta = con_sesion.post(
        f"{canal.prefijo}/siniestros",
        json={**SOLICITUD, **cambio},
        headers={"Idempotency-Key": CLAVE},
    )

    assert respuesta.status_code == 422
    assert respuesta.json()["campos"]
    assert _al_nucleo(nucleo) == []


def test_una_fecha_futura_es_422_sobre_el_campo_de_la_fecha(con_sesion, nucleo, canal):
    nucleo.responder("POST", NUCLEO, problema(400, "fecha_futura", "futura"))

    respuesta = con_sesion.post(
        f"{canal.prefijo}/siniestros", json=SOLICITUD, headers={"Idempotency-Key": CLAVE}
    )

    assert respuesta.status_code == 422
    assert respuesta.json()["codigo"] == "fecha_futura"
    assert respuesta.json()["campos"] == [{"campo": "ocurrido_el", "codigo": "fecha_futura"}]


def test_fuera_de_vigencia_explica_las_fechas_de_la_poliza(con_sesion, nucleo, canal):
    """Criterio 3: se explica por qué no procede."""
    nucleo.responder(
        "POST",
        NUCLEO,
        problema(
            409,
            "evento_fuera_de_vigencia",
            "fuera",
            vigente_desde="2025-09-26",
            vigente_hasta="2026-09-26",
        ),
    )

    respuesta = con_sesion.post(
        f"{canal.prefijo}/siniestros", json=SOLICITUD, headers={"Idempotency-Key": CLAVE}
    )

    assert respuesta.status_code == 409
    assert respuesta.json()["codigo"] == "evento_fuera_de_vigencia"
    assert respuesta.json()["vigente_hasta"] == "2026-09-26"
    assert respuesta.json()["vigente_desde"] == "2025-09-26"


@pytest.mark.parametrize(
    ("estado", "codigo"), [(404, "poliza_no_encontrada"), (409, "clave_idempotencia_en_uso")]
)
def test_los_errores_del_nucleo_llegan_con_su_codigo(con_sesion, nucleo, canal, estado, codigo):
    nucleo.responder("POST", NUCLEO, problema(estado, codigo))

    respuesta = con_sesion.post(
        f"{canal.prefijo}/siniestros", json=SOLICITUD, headers={"Idempotency-Key": CLAVE}
    )

    assert (respuesta.status_code, respuesta.json()["codigo"]) == (estado, codigo)


def test_el_mismo_siniestro_se_lista_y_se_consulta(con_sesion, nucleo, canal):
    nucleo.responder("GET", NUCLEO, 200, [DEL_NUCLEO])
    nucleo.responder("GET", f"{NUCLEO}/SIN-1", 200, DEL_NUCLEO)

    assert con_sesion.get(f"{canal.prefijo}/siniestros").json() == [PARA_EL_CANAL]
    assert con_sesion.get(f"{canal.prefijo}/siniestros/SIN-1").json() == PARA_EL_CANAL


def test_un_siniestro_ajeno_es_404_del_contrato(con_sesion, canal):
    respuesta = con_sesion.get(f"{canal.prefijo}/siniestros/SIN-OTRO")

    assert respuesta.status_code == 404
    assert respuesta.json()["codigo"] == "siniestro_no_encontrado"


def test_un_identificador_inseguro_es_404_sin_tocar_el_nucleo(con_sesion, nucleo, canal):
    respuesta = con_sesion.get(f"{canal.prefijo}/siniestros/SIN..1")

    assert respuesta.status_code == 404
    assert respuesta.json()["codigo"] == "siniestro_no_encontrado"
    assert _al_nucleo(nucleo) == []
