"""Contrato HTTP de las evidencias en `/v1/siniestros` (MOB-F05)."""

from __future__ import annotations

import json
from datetime import UTC, date, datetime, timedelta

import pytest
from fastapi.testclient import TestClient

from app.main import crear_app
from app.modulos.polizas.api import dependencias as polizas_dependencias
from app.modulos.siniestros.api import dependencias

CLIENTE = "CLI-2026-000318"
RUTA = f"/v1/siniestros/clientes/{CLIENTE}/siniestros"
FOTO = b"\xff\xd8\xff\xe0" + b"foto" * 100
REPORTE = {
    "numero_poliza": "POL-2026-000101-000318",
    "tipo_evento": "accidente_vehiculo",
    "ocurrido_el": (date.today() - timedelta(days=1)).isoformat(),
    "descripcion": "Choque leve en la calle 80 al frenar.",
}


@pytest.fixture
def nucleo(tmp_path, monkeypatch) -> TestClient:
    semilla = tmp_path / "polizas.json"
    semilla.write_text(
        json.dumps(
            [
                {
                    "numero": "POL-2026-000101",
                    "id_cliente": "*",
                    "producto": "auto",
                    "desde_dias": -30,
                    "hasta_dias": 335,
                }
            ]
        ),
        encoding="utf-8",
    )
    monkeypatch.setenv(polizas_dependencias.VARIABLE_SEMILLA, str(semilla))
    monkeypatch.delenv(dependencias.VARIABLE_ALMACENAMIENTO, raising=False)
    polizas_dependencias.reiniciar()
    dependencias.reiniciar()
    yield TestClient(crear_app())
    dependencias.reiniciar()
    polizas_dependencias.reiniciar()


@pytest.fixture
def id_siniestro(nucleo) -> str:
    return nucleo.post(RUTA, json=REPORTE).json()["id"]


def _adjuntar(nucleo, id_siniestro, contenido=FOTO, tipo="image/jpeg", **params):
    return nucleo.post(
        f"{RUTA}/{id_siniestro}/evidencias",
        content=contenido,
        headers={"Content-Type": tipo, "X-Canal": "movil"},
        params=params,
    )


def test_adjuntar_una_foto_responde_201_con_sus_metadatos(nucleo, id_siniestro):
    respuesta = _adjuntar(nucleo, id_siniestro, nombre_original="choque ñ.jpg")

    assert respuesta.status_code == 201
    cuerpo = respuesta.json()
    assert cuerpo["id"].startswith("EVI-")
    assert cuerpo["tipo_contenido"] == "image/jpeg"
    assert cuerpo["tamano"] == len(FOTO)
    assert cuerpo["nombre_original"] == "choque ñ.jpg"
    assert cuerpo["canal"] == "movil"


def test_la_evidencia_se_lista_y_se_descarga(nucleo, id_siniestro):
    evidencia = _adjuntar(nucleo, id_siniestro, nombre_original="choque ñ.jpg").json()

    lista = nucleo.get(f"{RUTA}/{id_siniestro}/evidencias").json()
    archivo = nucleo.get(f"{RUTA}/{id_siniestro}/evidencias/{evidencia['id']}")

    assert lista == [evidencia]
    assert archivo.content == FOTO
    assert archivo.headers["content-type"] == "image/jpeg"
    assert archivo.headers["x-content-type-options"] == "nosniff"
    assert "filename*=UTF-8''choque%20%C3%B1.jpg" in archivo.headers["content-disposition"]


def test_un_archivo_disfrazado_es_400_formato_no_admitido(nucleo, id_siniestro):
    respuesta = _adjuntar(nucleo, id_siniestro, contenido=b"MZ ejecutable")

    assert respuesta.status_code == 400
    assert respuesta.json()["title"] == "formato_no_admitido"


def test_mas_de_10_mb_se_corta_sin_leerlo_todo(nucleo, id_siniestro):
    grande = FOTO + b"0" * (10 * 1024 * 1024)

    respuesta = _adjuntar(nucleo, id_siniestro, contenido=grande)

    assert respuesta.status_code == 400
    assert respuesta.json()["title"] == "archivo_demasiado_grande"


def test_la_evidencia_de_un_siniestro_ajeno_es_404(nucleo, id_siniestro):
    evidencia = _adjuntar(nucleo, id_siniestro).json()
    ajena = f"/v1/siniestros/clientes/CLI-OTRO/siniestros/{id_siniestro}/evidencias"

    assert nucleo.get(ajena).status_code == 404
    assert nucleo.get(f"{ajena}/{evidencia['id']}").status_code == 404
    respuesta = nucleo.get(f"{RUTA}/{id_siniestro}/evidencias/EVI-nada")
    assert respuesta.json()["title"] == "evidencia_no_encontrada"


def test_sin_nombre_original_se_descarga_sin_nombre(nucleo, id_siniestro):
    evidencia = _adjuntar(nucleo, id_siniestro).json()

    archivo = nucleo.get(f"{RUTA}/{id_siniestro}/evidencias/{evidencia['id']}")

    assert archivo.headers["content-disposition"] == "inline"


def test_la_salud_reporta_el_almacen_en_uso(nucleo, monkeypatch):
    estado = nucleo.get("/salud").json()["modulos"]["siniestros"]["dependencias"]
    assert estado["almacen_evidencias"]["estado"] == "simulado"

    monkeypatch.setenv(dependencias.VARIABLE_ALMACENAMIENTO, "gcs")
    monkeypatch.setenv(dependencias.VARIABLE_BUCKET, "proyecto-solventa-evidencias")
    gcs = {d.nombre: d for d in dependencias.estado_dependencias()}["almacen_evidencias"]
    assert gcs.estado == "ok"
    assert "proyecto-solventa-evidencias" in gcs.detalle


def test_la_fecha_y_la_ubicacion_viajan_como_parametros(nucleo, id_siniestro):
    hace_una_hora = (datetime.now(UTC) - timedelta(hours=1)).replace(microsecond=0)
    respuesta = _adjuntar(
        nucleo,
        id_siniestro,
        tomada_en=hace_una_hora.isoformat(),
        latitud="4.6533",
        longitud="-74.0836",
    )

    cuerpo = respuesta.json()
    assert respuesta.status_code == 201
    assert (cuerpo["latitud"], cuerpo["longitud"]) == (4.6533, -74.0836)
    assert datetime.fromisoformat(cuerpo["tomada_en"]) == hace_una_hora
    assert cuerpo["lugar"] is None


def test_una_ubicacion_incompleta_es_400(nucleo, id_siniestro):
    respuesta = _adjuntar(nucleo, id_siniestro, latitud="4.65")

    assert respuesta.status_code == 400
    assert respuesta.json()["title"] == "ubicacion_invalida"
