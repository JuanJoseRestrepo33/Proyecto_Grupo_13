"""Pruebas de las rutas de evidencia de los dos routers (MOB-F05).

El canal manda un formulario multipart; el núcleo falso recibe el archivo como cuerpo crudo.
"""

from __future__ import annotations

from dataclasses import dataclass

import httpx
import pytest

from app.comun.evidencias import TAMANO_MAXIMO
from app.web.dependencias import COOKIE_SESION

from .conftest import CLIENTE, problema

NUCLEO = f"/v1/siniestros/clientes/{CLIENTE}/siniestros/SIN-1/evidencias"
FOTO = bytes([0xFF, 0xD8, 0xFF, 0xE0]) + b"foto" * 50
DEL_NUCLEO = {
    "id": "EVI-1",
    "tipo_contenido": "image/jpeg",
    "tamano": len(FOTO),
    "nombre_original": "choque.jpg",
    "canal": "movil",
    "cargada_en": "2026-10-26T15:00:00Z",
    "tomada_en": "2026-10-26T14:58:00Z",
    "latitud": 4.6533,
    "longitud": -74.0836,
    "lugar": None,
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


def _ruta(canal: Canal, sufijo: str = "") -> str:
    return f"{canal.prefijo}/siniestros/SIN-1/evidencias{sufijo}"


@pytest.mark.parametrize("metodo", ["GET", "POST"])
def test_sin_sesion_las_rutas_de_evidencia_son_401(cliente, nucleo, canal, metodo):
    """Lo que espera el humo de `cd.yml` (#177) en los dos routers."""
    respuesta = cliente.request(metodo, f"{canal.prefijo}/siniestros/humo-no-existe/evidencias")

    assert respuesta.status_code == 401
    assert _al_nucleo(nucleo) == []


def test_adjuntar_reenvia_el_archivo_crudo_con_su_tipo_y_su_captura(con_sesion, nucleo, canal):
    nucleo.responder("POST", NUCLEO, 201, DEL_NUCLEO)

    respuesta = con_sesion.post(
        _ruta(canal),
        files={"archivo": ("choque.jpg", FOTO, "image/jpeg")},
        data={"tomada_en": "2026-10-26T14:58:00Z", "latitud": "4.6533", "longitud": "-74.0836"},
    )

    assert respuesta.status_code == 201
    assert respuesta.json() == DEL_NUCLEO
    enviado = nucleo.ultima
    assert enviado.content == FOTO
    assert enviado.headers["Content-Type"] == "image/jpeg"
    assert enviado.headers["X-Canal"] == canal.nombre
    parametros = dict(enviado.url.params)
    assert parametros["nombre_original"] == "choque.jpg"
    assert parametros["latitud"] == "4.6533"
    assert parametros["tomada_en"].startswith("2026-10-26T14:58:00")


def test_sin_ubicacion_se_manda_el_lugar_escrito(con_sesion, nucleo, canal):
    nucleo.responder("POST", NUCLEO, 201, {**DEL_NUCLEO, "lugar": "Calle 80"})

    con_sesion.post(
        _ruta(canal),
        files={"archivo": ("choque.jpg", FOTO, "image/jpeg")},
        data={"lugar": "Calle 80"},
    )

    parametros = dict(nucleo.ultima.url.params)
    assert parametros["lugar"] == "Calle 80"
    assert "latitud" not in parametros


def test_mas_de_10_mb_es_413_sin_reenviar(con_sesion, nucleo, canal):
    grande = FOTO + b"0" * TAMANO_MAXIMO

    respuesta = con_sesion.post(_ruta(canal), files={"archivo": ("g.jpg", grande, "image/jpeg")})

    assert respuesta.status_code == 413
    assert respuesta.json()["codigo"] == "archivo_demasiado_grande"
    assert _al_nucleo(nucleo) == []


def test_sin_archivo_es_422(con_sesion, nucleo, canal):
    respuesta = con_sesion.post(_ruta(canal), data={"lugar": "Calle 80"})

    assert respuesta.status_code == 422
    assert respuesta.json()["campos"][0]["campo"] == "archivo"
    assert _al_nucleo(nucleo) == []


@pytest.mark.parametrize(
    ("codigo", "campo"),
    [
        ("formato_no_admitido", "archivo"),
        ("archivo_vacio", "archivo"),
        ("ubicacion_invalida", "latitud"),
        ("fecha_de_captura_invalida", "tomada_en"),
    ],
)
def test_los_400_de_datos_del_nucleo_son_422_con_su_campo(con_sesion, nucleo, canal, codigo, campo):
    nucleo.responder("POST", NUCLEO, problema(400, codigo, "no"))

    respuesta = con_sesion.post(_ruta(canal), files={"archivo": ("x.jpg", FOTO, "image/jpeg")})

    assert respuesta.status_code == 422
    assert respuesta.json()["codigo"] == codigo
    assert respuesta.json()["campos"] == [{"campo": campo, "codigo": codigo}]


def test_el_limite_del_nucleo_tambien_es_413(con_sesion, nucleo, canal):
    nucleo.responder("POST", NUCLEO, problema(400, "archivo_demasiado_grande"))

    respuesta = con_sesion.post(_ruta(canal), files={"archivo": ("x.jpg", FOTO, "image/jpeg")})

    assert respuesta.status_code == 413


@pytest.mark.parametrize(
    ("estado", "codigo"),
    [(409, "siniestro_cerrado"), (503, "almacenamiento_no_disponible")],
)
def test_otros_errores_del_nucleo_llegan_con_su_codigo(con_sesion, nucleo, canal, estado, codigo):
    nucleo.responder("POST", NUCLEO, problema(estado, codigo))

    respuesta = con_sesion.post(_ruta(canal), files={"archivo": ("x.jpg", FOTO, "image/jpeg")})

    assert (respuesta.status_code, respuesta.json()["codigo"]) == (estado, codigo)


def test_adjuntar_a_un_siniestro_ajeno_es_404_del_contrato(con_sesion, canal):
    respuesta = con_sesion.post(
        f"{canal.prefijo}/siniestros/SIN-OTRO/evidencias",
        files={"archivo": ("x.jpg", FOTO, "image/jpeg")},
    )

    assert respuesta.status_code == 404
    assert respuesta.json()["codigo"] == "siniestro_no_encontrado"


def test_un_archivo_sin_tipo_viaja_como_octet_stream(con_sesion, nucleo, canal):
    nucleo.responder("POST", NUCLEO, 201, DEL_NUCLEO)

    con_sesion.post(_ruta(canal), files={"archivo": ("x", FOTO, "")})

    assert nucleo.ultima.headers["Content-Type"] == "application/octet-stream"


def test_la_lista_es_la_del_mismo_siniestro_en_los_dos_canales(con_sesion, nucleo, canal):
    """Criterio 5: lo agregado desde un canal se ve en el otro."""
    nucleo.responder("GET", NUCLEO, 200, [DEL_NUCLEO, {**DEL_NUCLEO, "id": "EVI-2"}])

    respuesta = con_sesion.get(_ruta(canal))

    assert [e["id"] for e in respuesta.json()] == ["EVI-1", "EVI-2"]


def test_la_lista_de_un_siniestro_ajeno_es_404(con_sesion, canal):
    respuesta = con_sesion.get(f"{canal.prefijo}/siniestros/SIN-OTRO/evidencias")

    assert respuesta.json()["codigo"] == "siniestro_no_encontrado"


def test_descargar_entrega_el_archivo_con_su_tipo(con_sesion, nucleo, canal):
    nucleo.responder(
        "GET",
        f"{NUCLEO}/EVI-1",
        httpx.Response(
            200,
            content=FOTO,
            headers={
                "content-type": "image/jpeg",
                "content-disposition": "inline; filename*=UTF-8''choque.jpg",
            },
        ),
    )

    respuesta = con_sesion.get(_ruta(canal, "/EVI-1"))

    assert respuesta.content == FOTO
    assert respuesta.headers["content-type"] == "image/jpeg"
    assert respuesta.headers["content-disposition"] == "inline; filename*=UTF-8''choque.jpg"
    assert respuesta.headers["x-content-type-options"] == "nosniff"


def test_descargar_una_evidencia_inexistente_es_404(con_sesion, canal):
    respuesta = con_sesion.get(_ruta(canal, "/EVI-NADA"))

    assert respuesta.status_code == 404
    assert respuesta.json()["codigo"] == "evidencia_no_encontrada"
