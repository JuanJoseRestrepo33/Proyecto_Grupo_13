"""Pruebas de WEB-F04: consultar las pólizas y su detalle en `/v1/polizas`.

La póliza que se emite en la web (WEB-F03) es la misma que lista la app: los dos canales
leen de este módulo (criterio 2). Las pólizas de prueba de la semilla toman sus condiciones
del catálogo de cotización.
"""

from __future__ import annotations

import json
from dataclasses import replace

import pytest
from fastapi.testclient import TestClient

from app.main import crear_app
from app.modulos import cotizacion
from app.modulos.cotizacion.dominio import catalogo
from app.modulos.cotizacion.dominio.modelo import ProductoId
from app.modulos.polizas.api import dependencias
from app.modulos.polizas.infraestructura.catalogo_nucleo import CatalogoDelNucleo

from .test_emision_polizas import CLIENTE, SOLICITUD

RUTA = f"/v1/polizas/clientes/{CLIENTE}/polizas"


@pytest.fixture
def nucleo(tmp_path, monkeypatch) -> TestClient:
    semilla = tmp_path / "polizas.json"
    semilla.write_text(
        json.dumps(
            [
                {
                    "numero": "POL-STG-HOGAR",
                    "id_cliente": "*",
                    "producto": "hogar",
                    "desde_dias": -395,
                    "hasta_dias": -30,
                },
                {
                    "numero": "POL-STG-RARO",
                    "id_cliente": "*",
                    "producto": "producto_retirado",
                    "desde_dias": -400,
                    "hasta_dias": -40,
                },
            ]
        ),
        encoding="utf-8",
    )
    monkeypatch.setenv(dependencias.VARIABLE_SEMILLA, str(semilla))
    monkeypatch.delenv(dependencias.VARIABLE_DSN, raising=False)
    monkeypatch.delenv(dependencias.VARIABLE_ALMACENAMIENTO, raising=False)
    dependencias.reiniciar()
    yield TestClient(crear_app())
    dependencias.reiniciar()


@pytest.fixture
def emitida(nucleo):
    return dependencias.obtener_servicio().emitir(SOLICITUD)[0]


def test_la_lista_trae_producto_numero_vigencia_y_estado(nucleo, emitida):
    """Criterio 1. La emitida va primero: es la más reciente."""
    polizas = nucleo.get(RUTA).json()

    assert [p["numero"] for p in polizas] == [
        emitida.numero,
        f"POL-STG-HOGAR-{CLIENTE.rsplit('-', 1)[-1]}",
        f"POL-STG-RARO-{CLIENTE.rsplit('-', 1)[-1]}",
    ]
    primera = polizas[0]
    assert primera == {
        "numero": emitida.numero,
        "producto": "auto",
        "inicio": emitida.inicio.isoformat(),
        "fin": emitida.fin.isoformat(),
        "estado": primera["estado"],
        "nombre_producto": "Seguro de Auto Todo Riesgo",
        "prima_mensual": 132_900,
        "moneda": "COP",
        "tiene_documento": True,
        "emitida": True,
    }
    assert polizas[1]["estado"] == "vencida"
    assert polizas[1]["tiene_documento"] is False and polizas[1]["emitida"] is False


def test_entre_dos_del_mismo_dia_la_ultima_emitida_va_primero(nucleo, emitida):
    """Criterio 2. Las dos empiezan hoy; la recién emitida no puede quedar detrás de la otra."""
    segunda = dependencias.obtener_servicio().emitir(replace(SOLICITUD, id_suscripcion="SUS-2"))[0]
    assert segunda.inicio == emitida.inicio

    numeros = [p["numero"] for p in nucleo.get(RUTA).json()]

    assert numeros[:2] == [segunda.numero, emitida.numero]


def test_un_cliente_sin_polizas_recibe_la_lista_vacia(nucleo, monkeypatch):
    """Criterio 3: el canal muestra el estado vacío que lleva a cotizar."""
    monkeypatch.delenv(dependencias.VARIABLE_SEMILLA)
    dependencias.reiniciar()

    assert nucleo.get("/v1/polizas/clientes/CLI-NUEVO/polizas").json() == []


def test_el_detalle_de_una_emitida_trae_las_condiciones_de_su_oferta(nucleo, emitida):
    """Criterio 4."""
    detalle = nucleo.get(f"{RUTA}/{emitida.numero}").json()

    assert detalle["coberturas"] == [
        {"nombre": c.nombre, "limite": c.limite} for c in SOLICITUD.coberturas
    ]
    assert detalle["deducible"] == "5% del valor asegurado"
    assert detalle["valor_asegurado"] == "$60.000.000"
    assert detalle["prima_mensual"] == 132_900
    assert detalle["tiene_documento"] is True
    assert detalle["emitida_en"] is not None


def test_el_detalle_de_una_poliza_de_prueba_trae_las_del_catalogo(nucleo):
    numero = f"POL-STG-HOGAR-{CLIENTE.rsplit('-', 1)[-1]}"
    hogar = catalogo.obtener(ProductoId.HOGAR)

    detalle = nucleo.get(f"{RUTA}/{numero}").json()

    assert detalle["nombre_producto"] == hogar.nombre
    assert detalle["deducible"] == hogar.deducible
    assert [c["nombre"] for c in detalle["coberturas"]] == [c.nombre for c in hogar.coberturas]
    assert detalle["emitida"] is False and detalle["emitida_en"] is None


def test_una_poliza_de_prueba_sale_sin_prima(nucleo):
    """La prima estándar del catálogo no es la de nadie: el canal no debe mostrarla."""
    numero = f"POL-STG-HOGAR-{CLIENTE.rsplit('-', 1)[-1]}"

    detalle = nucleo.get(f"{RUTA}/{numero}").json()
    lista = nucleo.get(RUTA).json()

    assert detalle["prima_mensual"] is None and detalle["moneda"] is None
    assert next(p for p in lista if p["numero"] == numero)["prima_mensual"] is None


def test_una_poliza_de_un_producto_fuera_del_catalogo_sale_sin_condiciones(nucleo):
    numero = f"POL-STG-RARO-{CLIENTE.rsplit('-', 1)[-1]}"

    detalle = nucleo.get(f"{RUTA}/{numero}").json()

    assert detalle["coberturas"] == []
    assert detalle["deducible"] is None


def test_el_detalle_de_una_poliza_ajena_es_404(nucleo, emitida):
    respuesta = nucleo.get(f"/v1/polizas/clientes/CLI-OTRO/polizas/{emitida.numero}")

    assert respuesta.status_code == 404
    assert respuesta.json()["title"] == "poliza_no_encontrada"


def test_el_documento_de_una_poliza_de_prueba_no_esta_disponible(nucleo):
    numero = f"POL-STG-HOGAR-{CLIENTE.rsplit('-', 1)[-1]}"

    respuesta = nucleo.get(f"{RUTA}/{numero}/documento")

    assert respuesta.status_code == 404
    assert respuesta.json()["title"] == "documento_no_disponible"


def test_el_catalogo_del_nucleo_traduce_el_producto():
    condiciones = CatalogoDelNucleo().condiciones("auto")

    assert condiciones.nombre_producto == catalogo.obtener(ProductoId.AUTO).nombre
    assert CatalogoDelNucleo().condiciones("no_existe") is None


def test_un_producto_sin_entrada_en_el_catalogo_sale_sin_condiciones(monkeypatch):
    """Si se agrega un producto al enum sin su entrada, la lista no responde 404."""
    sin_auto = {k: v for k, v in catalogo.CATALOGO.items() if k is not ProductoId.AUTO}
    monkeypatch.setattr(catalogo, "CATALOGO", sin_auto)

    assert cotizacion.producto_del_catalogo("auto") is None
    assert CatalogoDelNucleo().condiciones("auto") is None
