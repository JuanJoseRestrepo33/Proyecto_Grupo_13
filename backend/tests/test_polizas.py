"""Pruebas del módulo de pólizas: vigencia, semilla de staging e interfaz pública (WEB-F05)."""

from __future__ import annotations

import json
from datetime import UTC, date, datetime

import pytest
from fastapi.testclient import TestClient

from app.compartido.reloj import RelojFijo
from app.main import crear_app
from app.modulos import polizas
from app.modulos.polizas.api import dependencias
from app.modulos.polizas.aplicacion.servicio import ServicioDePolizas
from app.modulos.polizas.dominio.modelo import EstadoPoliza, PlantillaDePoliza, Poliza
from app.modulos.polizas.infraestructura import semilla
from app.modulos.polizas.infraestructura.repositorio_memoria import RepositorioEnMemoria

HOY = date(2026, 10, 26)
CLIENTE = "CLI-2026-000318"
SEMILLA = [
    {
        "numero": "POL-A",
        "id_cliente": CLIENTE,
        "producto": "auto",
        "desde_dias": -30,
        "hasta_dias": 335,
    },
    {
        "numero": "POL-PLANTILLA",
        "id_cliente": "*",
        "producto": "hogar",
        "desde_dias": -395,
        "hasta_dias": -30,
    },
]


def test_la_vigencia_incluye_los_dos_extremos():
    poliza = Poliza("POL-1", CLIENTE, "auto", date(2026, 1, 1), date(2026, 1, 31))

    assert poliza.cubre(date(2026, 1, 1))
    assert poliza.cubre(date(2026, 1, 31))
    assert not poliza.cubre(date(2026, 2, 1))
    assert not poliza.cubre(date(2025, 12, 31))


def test_el_estado_depende_del_dia():
    poliza = Poliza("POL-1", CLIENTE, "auto", date(2026, 1, 1), date(2026, 1, 31))

    assert poliza.estado(date(2025, 12, 1)) is EstadoPoliza.POR_INICIAR
    assert poliza.estado(date(2026, 1, 15)) is EstadoPoliza.VIGENTE
    assert poliza.estado(date(2026, 2, 1)) is EstadoPoliza.VENCIDA


def test_una_poliza_que_termina_antes_de_empezar_no_existe():
    with pytest.raises(ValueError):
        Poliza("POL-1", CLIENTE, "auto", date(2026, 2, 1), date(2026, 1, 1))


def test_la_semilla_usa_fechas_relativas_al_arranque():
    cargada = semilla.interpretar(SEMILLA, HOY)

    (propia,) = cargada.polizas
    assert (propia.inicio, propia.fin) == (date(2026, 9, 26), date(2027, 9, 26))
    (plantilla,) = cargada.plantillas
    assert plantilla.fin == date(2026, 9, 26)


@pytest.mark.parametrize(
    "entradas", [{"no": "lista"}, [{"numero": "x"}], [{**SEMILLA[0], "desde_dias": "ayer"}]]
)
def test_una_semilla_mal_formada_se_rechaza(entradas):
    with pytest.raises(semilla.SemillaInvalidaError):
        semilla.interpretar(entradas, HOY)


def test_un_archivo_de_semilla_ilegible_se_rechaza(tmp_path):
    archivo = tmp_path / "semilla.json"
    archivo.write_text("{no es json", encoding="utf-8")

    with pytest.raises(semilla.SemillaInvalidaError):
        semilla.cargar(archivo, HOY)
    with pytest.raises(semilla.SemillaInvalidaError):
        semilla.cargar(tmp_path / "no-existe.json", HOY)


@pytest.fixture
def servicio() -> ServicioDePolizas:
    cargada = semilla.interpretar(SEMILLA, HOY)
    repositorio = RepositorioEnMemoria()
    for poliza in cargada.polizas:
        repositorio.guardar(poliza)
    reloj = RelojFijo(datetime(2026, 10, 26, 15, 0, tzinfo=UTC))
    return ServicioDePolizas(repositorio, reloj, cargada.plantillas)


def test_la_plantilla_la_tiene_cualquier_cliente_con_numero_propio(servicio):
    del_cliente = servicio.del_cliente(CLIENTE)
    de_otro = servicio.del_cliente("CLI-2026-000999")

    assert [p.numero for p in del_cliente] == ["POL-A", "POL-PLANTILLA-000318"]
    assert [p.numero for p in de_otro] == ["POL-PLANTILLA-000999"]


def test_la_poliza_de_otro_cliente_no_existe_para_este(servicio):
    assert servicio.poliza_del_cliente("POL-A", CLIENTE).producto == "auto"
    assert servicio.poliza_del_cliente("POL-A", "CLI-2026-000999") is None
    assert servicio.poliza_del_cliente("POL-PLANTILLA-000318", "CLI-2026-000999") is None
    assert servicio.poliza_del_cliente("POL-PLANTILLA-000999", "CLI-2026-000999") is not None
    assert servicio.poliza_del_cliente("POL-NADA", CLIENTE) is None


def test_la_plantilla_genera_el_numero_con_el_consecutivo_del_cliente():
    plantilla = PlantillaDePoliza("POL-X", "viaje", HOY, HOY)

    assert plantilla.para("CLI-2026-000042").numero == "POL-X-000042"


# -- composición y HTTP -------------------------------------------------------------------


@pytest.fixture
def con_semilla(tmp_path, monkeypatch):
    archivo = tmp_path / "semilla.json"
    archivo.write_text(json.dumps(SEMILLA), encoding="utf-8")
    monkeypatch.setenv(dependencias.VARIABLE_SEMILLA, str(archivo))
    dependencias.reiniciar()
    yield TestClient(crear_app())
    dependencias.reiniciar()


def test_las_polizas_del_cliente_por_http(con_semilla):
    respuesta = con_semilla.get(f"/v1/polizas/clientes/{CLIENTE}/polizas")

    assert respuesta.status_code == 200
    assert [(p["numero"], p["estado"]) for p in respuesta.json()] == [
        ("POL-A", "vigente"),
        ("POL-PLANTILLA-000318", "vencida"),
    ]


def test_la_salud_dice_cuantas_polizas_de_prueba_hay(con_semilla):
    dependencias_polizas = con_semilla.get("/salud").json()["modulos"]["polizas"]["dependencias"]

    assert (
        dependencias_polizas["semilla_polizas"]["detalle"] == "1 pólizas y 1 plantillas de prueba"
    )


def test_una_semilla_ilegible_no_tumba_el_nucleo(tmp_path, monkeypatch):
    archivo = tmp_path / "semilla.json"
    archivo.write_text("[{}]", encoding="utf-8")
    monkeypatch.setenv(dependencias.VARIABLE_SEMILLA, str(archivo))
    dependencias.reiniciar()
    try:
        estado = {d.nombre: d for d in dependencias.estado_dependencias()}
        assert polizas.obtener_servicio().del_cliente(CLIENTE) == ()
    finally:
        dependencias.reiniciar()

    assert estado["semilla_polizas"].estado == "degradado"


def test_sin_semilla_no_hay_polizas(monkeypatch):
    monkeypatch.delenv(dependencias.VARIABLE_SEMILLA, raising=False)
    dependencias.reiniciar()
    try:
        assert polizas.obtener_servicio().del_cliente(CLIENTE) == ()
    finally:
        dependencias.reiniciar()
