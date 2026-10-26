"""Reglas de la evidencia antes del almacén: tamaño, formato y contenido real (MOB-F05)."""

from __future__ import annotations

import pytest

from app.modulos.siniestros.dominio.evidencias import (
    TAMANO_MAXIMO,
    ArchivoDemasiadoGrandeError,
    ArchivoVacioError,
    FormatoNoAdmitidoError,
    exigir_evidencia_valida,
    tipo_real,
)

JPEG = b"\xff\xd8\xff\xe0" + b"0" * 100
PNG = b"\x89PNG\r\n\x1a\n" + b"0" * 100
PDF = b"%PDF-1.7\n" + b"0" * 100
MP4 = b"\x00\x00\x00\x18ftypisom" + b"0" * 100
HEIC = b"\x00\x00\x00\x18ftypheic" + b"0" * 100


@pytest.mark.parametrize(
    ("contenido", "tipo"),
    [
        (JPEG, "image/jpeg"),
        (PNG, "image/png"),
        (PDF, "application/pdf"),
        (MP4, "video/mp4"),
        (HEIC, "image/heic"),
        (b"MZ\x90\x00 ejecutable", None),
        (b"", None),
    ],
)
def test_el_formato_sale_de_los_primeros_bytes(contenido, tipo):
    assert tipo_real(contenido) == tipo


@pytest.mark.parametrize(
    ("declarado", "contenido", "esperado"),
    [
        ("image/jpeg", JPEG, "image/jpeg"),
        ("image/jpg", JPEG, "image/jpeg"),
        ("IMAGE/PNG; charset=binary", PNG, "image/png"),
        ("application/pdf", PDF, "application/pdf"),
        ("video/quicktime", MP4, "video/mp4"),
        ("image/heic", HEIC, "image/heic"),
    ],
)
def test_un_archivo_admitido_pasa_con_su_tipo_normalizado(declarado, contenido, esperado):
    assert exigir_evidencia_valida(declarado, contenido) == esperado


@pytest.mark.parametrize(
    ("declarado", "contenido"),
    [
        ("image/jpeg", b"MZ\x90\x00 un ejecutable renombrado"),
        ("image/png", JPEG),
        ("application/zip", b"PK\x03\x04"),
        ("text/plain", b"hola"),
    ],
)
def test_un_formato_no_admitido_o_disfrazado_se_rechaza(declarado, contenido):
    with pytest.raises(FormatoNoAdmitidoError) as error:
        exigir_evidencia_valida(declarado, contenido)

    assert "image/jpeg" in error.value.detalles["admitidos"]


def test_mas_de_10_mb_se_rechaza_y_10_mb_justos_pasan():
    justo = JPEG + b"0" * (TAMANO_MAXIMO - len(JPEG))

    assert exigir_evidencia_valida("image/jpeg", justo) == "image/jpeg"
    with pytest.raises(ArchivoDemasiadoGrandeError) as error:
        exigir_evidencia_valida("image/jpeg", justo + b"0")
    assert error.value.detalles["maximo"] == TAMANO_MAXIMO


def test_un_archivo_vacio_se_rechaza():
    with pytest.raises(ArchivoVacioError):
        exigir_evidencia_valida("image/jpeg", b"")
