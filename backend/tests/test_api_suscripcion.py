"""Contrato HTTP del checkout en `/v1/suscripcion` (WEB-F03), con la app armada de verdad.

Recorre lo mismo que el E2E web: cotizar → aceptar → pagar → firmar → descargar el documento
→ consultar la póliza y el log de auditoría.
"""

from __future__ import annotations

import uuid

import pytest
from fastapi.testclient import TestClient

from app.main import crear_app
from app.modulos.auditoria.api import dependencias as auditoria_dependencias
from app.modulos.cotizacion.api import dependencias as cotizacion_dependencias
from app.modulos.pagos.api import dependencias as pagos_dependencias
from app.modulos.pagos.dominio.modelo import EstadoPago, MedioPago
from app.modulos.polizas.api import dependencias as polizas_dependencias
from app.modulos.suscripcion.api import dependencias

CLIENTE = "CLI-2026-000318"
RUTA = f"/v1/suscripcion/clientes/{CLIENTE}/suscripciones"
MODULOS = (
    auditoria_dependencias,
    cotizacion_dependencias,
    pagos_dependencias,
    polizas_dependencias,
    dependencias,
)
VARIABLES = (
    "AUDITORIA_DATABASE_URL",
    "COTIZACION_DATABASE_URL",
    "PAGOS_DATABASE_URL",
    "POLIZAS_DATABASE_URL",
    "SUSCRIPCION_DATABASE_URL",
    "POLIZAS_SEMILLA",
    "PASARELA_MODO",
    "FIRMA_MODO",
)


def _reiniciar() -> None:
    for modulo in MODULOS:
        modulo.reiniciar()


@pytest.fixture
def nucleo(monkeypatch) -> TestClient:
    for variable in VARIABLES:
        monkeypatch.delenv(variable, raising=False)
    _reiniciar()
    yield TestClient(crear_app())
    _reiniciar()


def _cotizar(nucleo, id_cliente=CLIENTE) -> str:
    respuesta = nucleo.post(
        "/v1/cotizacion/cotizaciones",
        json={
            "producto": "auto",
            "asegurado": {"edad": 34, "ciudad": "Bogotá", "valor_asegurado": 60_000_000},
            "id_cliente": id_cliente,
            "usar_open_finance": False,
        },
        headers={"X-Canal": "web"},
    )
    assert respuesta.status_code == 201, respuesta.text
    return respuesta.json()["id_cotizacion"]


def _aceptar(nucleo, id_cotizacion):
    return nucleo.post(RUTA, json={"id_cotizacion": id_cotizacion}, headers={"X-Canal": "web"})


def _pagar(nucleo, id_suscripcion, medio="tarjeta_credito", clave=None):
    return nucleo.post(
        f"{RUTA}/{id_suscripcion}/pagos",
        json={"medio": medio},
        headers={"Idempotency-Key": clave or str(uuid.uuid4()), "X-Canal": "web"},
    )


def _firmar(nucleo, id_suscripcion, acepta=True):
    return nucleo.post(f"{RUTA}/{id_suscripcion}/firma", json={"acepta_condiciones": acepta})


def test_el_recorrido_completo_deja_una_poliza_emitida_y_auditada(nucleo):
    id_cotizacion = _cotizar(nucleo)

    aceptada = _aceptar(nucleo, id_cotizacion)
    assert aceptada.status_code == 201
    suscripcion = aceptada.json()
    assert suscripcion["estado"] == "aceptada"
    assert suscripcion["pago"] is None and suscripcion["poliza"] is None
    id_suscripcion = suscripcion["id"]

    pago = _pagar(nucleo, id_suscripcion)
    assert pago.status_code == 201
    assert pago.json()["estado"] == "aprobado"
    assert pago.json()["reintento"] is False
    assert pago.json()["monto"] == suscripcion["prima_mensual"]

    firmada = _firmar(nucleo, id_suscripcion)
    assert firmada.status_code == 201
    emitida = firmada.json()
    assert emitida["estado"] == "emitida"
    assert emitida["pago"]["estado"] == "aprobado"
    numero = emitida["poliza"]["numero"]
    assert numero.startswith("POL-")

    documento = nucleo.get(f"/v1/polizas/clientes/{CLIENTE}/polizas/{numero}/documento")
    assert documento.status_code == 200
    assert documento.content.startswith(b"%PDF")

    polizas = nucleo.get(f"/v1/polizas/clientes/{CLIENTE}/polizas").json()
    assert [p["numero"] for p in polizas] == [numero]
    assert polizas[0]["estado"] == "vigente"

    pasos = [p["paso"] for p in emitida["trazabilidad"]]
    assert pasos == ["aceptacion", "pago", "firma", "emision"]
    for paso in emitida["trazabilidad"]:
        registro = nucleo.get(f"/v1/auditoria/registros/{paso['id_decision']}")
        assert registro.status_code == 200, paso
    assert nucleo.get("/v1/auditoria/integridad").json()["conforme"] is True

    assert nucleo.get(f"{RUTA}/{id_suscripcion}").json() == emitida
    assert [s["id"] for s in nucleo.get(RUTA).json()] == [id_suscripcion]


def test_aceptar_dos_veces_responde_200_con_la_misma(nucleo):
    id_cotizacion = _cotizar(nucleo)
    primera = _aceptar(nucleo, id_cotizacion).json()

    segunda = _aceptar(nucleo, id_cotizacion)

    assert segunda.status_code == 200
    assert segunda.json()["id"] == primera["id"]


def test_una_cotizacion_vencida_responde_409(nucleo, monkeypatch):
    id_cotizacion = _cotizar(nucleo)
    servicio = dependencias.obtener_servicio()
    reloj = servicio._reloj
    instante = reloj.ahora()

    class Despues:
        def ahora(self):
            from datetime import timedelta

            return instante + timedelta(minutes=6)

    monkeypatch.setattr(servicio, "_reloj", Despues())
    respuesta = _aceptar(nucleo, id_cotizacion)

    assert respuesta.status_code == 409
    assert respuesta.json()["title"] == "cotizacion_vencida"


def test_una_cotizacion_ajena_responde_404(nucleo):
    respuesta = _aceptar(nucleo, _cotizar(nucleo, "CLI-OTRO"))

    assert respuesta.status_code == 404
    assert respuesta.json()["title"] == "cotizacion_no_encontrada"


def test_la_misma_clave_responde_200_con_reintento_y_otra_responde_409(nucleo):
    id_suscripcion = _aceptar(nucleo, _cotizar(nucleo)).json()["id"]
    clave = str(uuid.uuid4())
    primero = _pagar(nucleo, id_suscripcion, clave=clave).json()

    repetido = _pagar(nucleo, id_suscripcion, clave=clave)
    otro = _pagar(nucleo, id_suscripcion)

    assert repetido.status_code == 200
    assert repetido.json() == {**primero, "reintento": True}
    assert otro.status_code == 409
    assert otro.json()["title"] == "pago_ya_registrado"
    assert len(pagos_dependencias.obtener_pasarela().cobros) == 1


def test_pse_pendiente_se_confirma_al_consultar(nucleo):
    id_suscripcion = _aceptar(nucleo, _cotizar(nucleo)).json()["id"]
    pendiente = _pagar(nucleo, id_suscripcion, medio="pse").json()
    assert pendiente["estado"] == "pendiente"

    consultado = nucleo.get(f"{RUTA}/{id_suscripcion}/pagos/{pendiente['id']}")

    assert consultado.json()["estado"] == "aprobado"
    assert nucleo.get(f"{RUTA}/{id_suscripcion}").json()["estado"] == "pagada"
    leido = nucleo.get(
        f"/v1/pagos/clientes/{CLIENTE}/conceptos/{id_suscripcion}/pagos/{pendiente['id']}"
    )
    assert leido.json()["estado"] == "aprobado"


def test_un_rechazo_se_informa_y_permite_otro_intento(nucleo):
    id_suscripcion = _aceptar(nucleo, _cotizar(nucleo)).json()["id"]
    pasarela = pagos_dependencias.obtener_pasarela()
    pasarela.resultados[MedioPago.TARJETA_CREDITO] = EstadoPago.RECHAZADO

    rechazado = _pagar(nucleo, id_suscripcion)
    pasarela.resultados[MedioPago.TARJETA_CREDITO] = EstadoPago.APROBADO
    aprobado = _pagar(nucleo, id_suscripcion)

    assert rechazado.json()["estado"] == "rechazado"
    assert aprobado.status_code == 201 and aprobado.json()["estado"] == "aprobado"


def test_con_la_pasarela_caida_responde_503_sin_cobrar(nucleo):
    id_suscripcion = _aceptar(nucleo, _cotizar(nucleo)).json()["id"]
    pagos_dependencias.obtener_pasarela().caida = True

    respuesta = _pagar(nucleo, id_suscripcion)

    assert respuesta.status_code == 503
    assert nucleo.get(f"{RUTA}/{id_suscripcion}").json()["pago"] is None


def test_pagar_sin_clave_es_422(nucleo):
    id_suscripcion = _aceptar(nucleo, _cotizar(nucleo)).json()["id"]

    respuesta = nucleo.post(f"{RUTA}/{id_suscripcion}/pagos", json={"medio": "pse"})

    assert respuesta.status_code == 422


def test_firmar_sin_pago_es_409_y_sin_aceptar_es_422(nucleo):
    id_suscripcion = _aceptar(nucleo, _cotizar(nucleo)).json()["id"]

    sin_pago = _firmar(nucleo, id_suscripcion)
    _pagar(nucleo, id_suscripcion)
    sin_aceptar = _firmar(nucleo, id_suscripcion, acepta=False)

    assert sin_pago.status_code == 409
    assert sin_pago.json()["title"] == "pago_no_aprobado"
    assert sin_aceptar.status_code == 422
    assert sin_aceptar.json()["title"] == "aceptacion_requerida"


def test_firmar_dos_veces_responde_200_con_la_misma_poliza(nucleo):
    id_suscripcion = _aceptar(nucleo, _cotizar(nucleo)).json()["id"]
    _pagar(nucleo, id_suscripcion)
    primera = _firmar(nucleo, id_suscripcion).json()

    segunda = _firmar(nucleo, id_suscripcion)

    assert segunda.status_code == 200
    assert segunda.json()["poliza"] == primera["poliza"]


def test_la_suscripcion_de_otro_cliente_es_404(nucleo):
    id_suscripcion = _aceptar(nucleo, _cotizar(nucleo)).json()["id"]

    respuesta = nucleo.get(f"/v1/suscripcion/clientes/CLI-OTRO/suscripciones/{id_suscripcion}")

    assert respuesta.status_code == 404
    assert respuesta.json()["title"] == "suscripcion_no_encontrada"


def test_salud_reporta_el_checkout(nucleo):
    modulos = nucleo.get("/salud").json()["modulos"]

    assert modulos["suscripcion"]["dependencias"]["firma_electronica"]["estado"] == "simulado"
    assert modulos["pagos"]["dependencias"]["pasarela_pagos"]["estado"] == "simulado"


def test_salud_reporta_la_pasarela_y_la_firma_caidas(monkeypatch, nucleo):
    monkeypatch.setenv("PASARELA_MODO", "caida")
    monkeypatch.setenv("FIRMA_MODO", "caida")
    _reiniciar()

    modulos = nucleo.get("/salud").json()["modulos"]

    assert modulos["pagos"]["dependencias"]["pasarela_pagos"]["estado"] == "caido"
    assert modulos["suscripcion"]["dependencias"]["firma_electronica"]["estado"] == "caido"
