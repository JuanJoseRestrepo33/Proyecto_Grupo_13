"""Pruebas de ARQ-01: esqueleto del monolito modular con fachada versionada.

Cada prueba corresponde a un criterio de aceptación de la historia. La frontera entre
módulos (criterio 2) no se prueba aquí sino con `lint-imports` en el pipeline.
"""

from __future__ import annotations

import importlib
import sys
from pathlib import Path

import pytest
from fastapi import APIRouter
from fastapi.testclient import TestClient

from app.compartido.modulo import DescriptorDeModulo, EstadoDeDependencia
from app.fachada.registro import ModuloMalFormado, descubrir_modulos
from app.main import crear_app
from scripts.nuevo_modulo import ModuloYaExiste, NombreDeModuloInvalido, crear_modulo

CAPACIDADES_DEL_DISENO = {
    "auditoria",
    "identidad",
    "consentimiento",
    "perfilamiento",
    "cotizacion",
    "suscripcion",
    "polizas",
    "siniestros",
    "pagos",
    "socios",
}


@pytest.fixture
def cliente() -> TestClient:
    return TestClient(crear_app())


def _descriptor(nombre: str, *dependencias: EstadoDeDependencia, con_ruta: bool = True):
    router = APIRouter(prefix=f"/{nombre}")
    if con_ruta:
        router.add_api_route("/", lambda: {}, methods=["GET"])
    return DescriptorDeModulo(
        nombre=nombre,
        descripcion=f"módulo de prueba {nombre}",
        historia="PRUEBA",
        router=router,
        dependencias=lambda: dependencias,
    )


# --- Criterio 1: un módulo por capacidad, cada uno con su descriptor ----------------------


def test_existe_un_modulo_por_capacidad_de_negocio():
    nombres = {m.nombre for m in descubrir_modulos()}
    assert nombres == CAPACIDADES_DEL_DISENO


def test_cada_modulo_tiene_las_cuatro_capas():
    raiz = Path(__file__).resolve().parent.parent / "app" / "modulos"
    for modulo in descubrir_modulos():
        for capa in ("dominio", "aplicacion", "infraestructura", "api"):
            assert (raiz / modulo.nombre / capa / "__init__.py").exists(), (modulo.nombre, capa)


# --- Criterio 3: la fachada /v1 publica el contrato y lista las capacidades ----------------


def test_indice_v1_distingue_capacidades_publicadas_de_esqueletos(cliente):
    cuerpo = cliente.get("/v1/").json()
    assert cuerpo["version"] == "v1"
    assert cuerpo["capacidades"] == [
        "auditoria",
        "consentimiento",
        "cotizacion",
        "identidad",
        "perfilamiento",
        "polizas",
    ]
    assert set(cuerpo["modulos"]) == CAPACIDADES_DEL_DISENO
    assert cuerpo["modulos"]["consentimiento"]["historia"] == "WEB-F02"


def test_las_rutas_de_cotizacion_siguen_bajo_v1_tras_la_reorganizacion(cliente):
    assert cliente.get("/v1/cotizacion/productos").status_code == 200


# --- Criterio 4: /salud reporta cada módulo y cada dependencia por separado ----------------


def test_salud_reporta_cada_modulo_y_cada_dependencia(cliente):
    respuesta = cliente.get("/salud")
    assert respuesta.status_code == 200
    cuerpo = respuesta.json()
    assert cuerpo["estado"] == "ok"
    assert set(cuerpo["modulos"]) == CAPACIDADES_DEL_DISENO
    assert cuerpo["modulos"]["cotizacion"]["estado"] == "ok"
    assert cuerpo["modulos"]["pagos"]["estado"] == "esqueleto"
    dependencias = cuerpo["modulos"]["cotizacion"]["dependencias"]
    assert dependencias["repositorio_cotizaciones"]["estado"] == "simulado"
    assert cuerpo["dependencias"]["perfilamiento"]["usada_por"] == ["cotizacion"]


def test_la_fachada_invoca_el_arranque_de_cada_modulo():
    arrancados: list[str] = []
    modulos = [
        DescriptorDeModulo(
            nombre=nombre,
            descripcion="",
            historia="PRUEBA",
            router=APIRouter(),
            iniciar=lambda nombre=nombre: arrancados.append(nombre),
        )
        for nombre in ("a", "b")
    ]

    crear_app(modulos)

    assert arrancados == ["a", "b"]


def test_la_fachada_detiene_cada_modulo_en_orden_inverso_al_apagar():
    detenidos: list[str] = []
    modulos = [
        DescriptorDeModulo(
            nombre=nombre,
            descripcion="",
            historia="PRUEBA",
            router=APIRouter(),
            detener=lambda nombre=nombre: detenidos.append(nombre),
        )
        for nombre in ("a", "b")
    ]

    with TestClient(crear_app(modulos)):
        assert detenidos == []

    assert detenidos == ["b", "a"]


def test_salud_degrada_el_nucleo_si_una_dependencia_se_degrada():
    app = crear_app(
        [
            _descriptor("sano", EstadoDeDependencia("base_datos", "ok")),
            _descriptor("tocado", EstadoDeDependencia("open_finance", "degradado", "p95 > 700 ms")),
        ]
    )
    cuerpo = TestClient(app).get("/salud").json()
    assert cuerpo["estado"] == "degradado"
    assert cuerpo["modulos"]["sano"]["estado"] == "ok"
    assert cuerpo["modulos"]["tocado"]["estado"] == "degradado"
    assert cuerpo["dependencias"]["open_finance"]["detalle"] == "p95 > 700 ms"


def test_salud_responde_503_si_una_dependencia_esta_caida():
    app = crear_app([_descriptor("pagos", EstadoDeDependencia("pasarela", "caido"))])
    respuesta = TestClient(app).get("/salud")
    assert respuesta.status_code == 503
    assert respuesta.json()["estado"] == "caido"


def test_una_dependencia_compartida_aparece_una_vez_con_el_peor_estado():
    app = crear_app(
        [
            _descriptor("a", EstadoDeDependencia("base_datos", "ok")),
            _descriptor("b", EstadoDeDependencia("base_datos", "degradado")),
        ]
    )
    dependencias = TestClient(app).get("/salud").json()["dependencias"]
    assert dependencias["base_datos"]["estado"] == "degradado"
    assert dependencias["base_datos"]["usada_por"] == ["a", "b"]


# --- Criterio 5: un módulo creado desde la plantilla queda registrado solo ------------------


@pytest.fixture
def paquete_temporal(tmp_path, monkeypatch):
    """Un paquete `modulos_prueba` vacío, importable, que se limpia al terminar."""
    raiz = tmp_path / "modulos_prueba"
    raiz.mkdir()
    (raiz / "__init__.py").write_text("", encoding="utf-8")
    monkeypatch.syspath_prepend(str(tmp_path))
    importlib.invalidate_caches()
    yield raiz
    for nombre in [n for n in sys.modules if n.startswith("modulos_prueba")]:
        del sys.modules[nombre]


def test_un_modulo_nuevo_desde_la_plantilla_queda_registrado_sin_tocar_los_demas(
    paquete_temporal,
):
    crear_modulo("garantias", "WEB-F99", "Garantías extendidas", raiz=paquete_temporal)
    crear_modulo("asistencias", "WEB-F98", "Asistencias en viaje", raiz=paquete_temporal)

    paquete = importlib.import_module("modulos_prueba")
    modulos = descubrir_modulos(paquete)

    assert [m.nombre for m in modulos] == ["asistencias", "garantias"]
    assert modulos[1].historia == "WEB-F99"
    cliente = TestClient(crear_app(modulos))
    indice = cliente.get("/v1/").json()
    assert set(indice["modulos"]) == {"asistencias", "garantias"}
    assert indice["capacidades"] == []
    assert cliente.get("/salud").json()["modulos"]["garantias"]["estado"] == "esqueleto"


def test_la_plantilla_rechaza_nombres_invalidos_y_duplicados(tmp_path):
    with pytest.raises(NombreDeModuloInvalido):
        crear_modulo("Pólizas", "X", "x", raiz=tmp_path)
    with pytest.raises(NombreDeModuloInvalido):
        crear_modulo("class", "X", "x", raiz=tmp_path)
    crear_modulo("polizas", "X", "x", raiz=tmp_path)
    with pytest.raises(ModuloYaExiste):
        crear_modulo("polizas", "X", "x", raiz=tmp_path)


def test_un_paquete_sin_descriptor_rompe_el_arranque_con_mensaje_claro(paquete_temporal):
    (paquete_temporal / "huerfano").mkdir()
    (paquete_temporal / "huerfano" / "__init__.py").write_text("", encoding="utf-8")
    paquete = importlib.import_module("modulos_prueba")
    with pytest.raises(ModuloMalFormado, match="huerfano no expone"):
        descubrir_modulos(paquete)


def test_un_descriptor_con_nombre_distinto_al_paquete_se_rechaza(paquete_temporal):
    crear_modulo("real", "X", "x", raiz=paquete_temporal)
    archivo = paquete_temporal / "real" / "__init__.py"
    contenido = archivo.read_text(encoding="utf-8").replace('"real"', '"otro"')
    archivo.write_text(contenido, encoding="utf-8")
    paquete = importlib.import_module("modulos_prueba")
    with pytest.raises(ModuloMalFormado, match="Deben coincidir"):
        descubrir_modulos(paquete)
