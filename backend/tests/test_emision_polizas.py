"""Pruebas de WEB-F03, criterio 4: la póliza queda emitida con su número y un documento.

La emisión la pide suscripción por la interfaz pública de pólizas; aquí se prueba el módulo
de pólizas solo, contra el repositorio en memoria. Contra PostgreSQL están en
`test_polizas_postgres.py`.
"""

from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, date, datetime

import pytest
from fastapi.testclient import TestClient

from app.compartido.dinero import Dinero
from app.compartido.reloj import RelojFijo
from app.main import crear_app
from app.modulos.polizas.api import dependencias
from app.modulos.polizas.aplicacion.servicio import ServicioDePolizas
from app.modulos.polizas.dominio.errores import (
    AlmacenDeDocumentosNoDisponibleError,
    DocumentoNoDisponibleError,
    PolizaNoEncontradaError,
)
from app.modulos.polizas.dominio.modelo import (
    CoberturaPoliza,
    EstadoPoliza,
    Poliza,
    SolicitudDeEmision,
    vigencia_anual,
)
from app.modulos.polizas.infraestructura.almacen_documentos import (
    AlmacenDeDocumentosEnMemoria,
    AlmacenDeDocumentosGcs,
    nombre_del_objeto,
)
from app.modulos.polizas.infraestructura.documento_pdf import GeneradorPdf
from app.modulos.polizas.infraestructura.repositorio_memoria import RepositorioEnMemoria

INSTANTE = datetime(2026, 11, 8, 15, 0, tzinfo=UTC)
CLIENTE = "CLI-2026-000318"
SOLICITUD = SolicitudDeEmision(
    id_suscripcion="SUS-1",
    id_cliente=CLIENTE,
    producto="auto",
    nombre_producto="Seguro de Auto Todo Riesgo",
    coberturas=(
        CoberturaPoliza("Responsabilidad civil", "$1.000.000.000"),
        CoberturaPoliza("Pérdida total por daños", "100% del valor asegurado"),
    ),
    deducible="5% del valor asegurado",
    valor_asegurado="$60.000.000",
    prima_mensual=Dinero.cop(132_900),
    id_firma="FIR-1",
    canal="web",
)


class AuditoriaEnMemoria:
    def __init__(self) -> None:
        self.emitidas: list[str] = []

    def registrar_emision(self, poliza) -> str:
        self.emitidas.append(poliza.numero)
        return f"DEC-{len(self.emitidas)}"


@pytest.fixture
def auditoria() -> AuditoriaEnMemoria:
    return AuditoriaEnMemoria()


@pytest.fixture
def almacen() -> AlmacenDeDocumentosEnMemoria:
    return AlmacenDeDocumentosEnMemoria()


@pytest.fixture
def servicio(almacen, auditoria) -> ServicioDePolizas:
    return ServicioDePolizas(
        RepositorioEnMemoria(),
        RelojFijo(INSTANTE),
        almacen=almacen,
        generador=GeneradorPdf(),
        auditoria=auditoria,
    )


def test_emitir_deja_la_poliza_anual_con_las_condiciones_de_la_oferta(servicio, auditoria):
    poliza, nueva = servicio.emitir(SOLICITUD)

    assert nueva
    assert poliza.numero == "POL-2026-000001"
    assert (poliza.inicio, poliza.fin) == (date(2026, 11, 8), date(2027, 11, 7))
    assert poliza.estado(INSTANTE.date()) is EstadoPoliza.VIGENTE
    assert poliza.coberturas == SOLICITUD.coberturas
    assert poliza.prima_mensual == Dinero.cop(132_900)
    assert (poliza.id_suscripcion, poliza.id_firma, poliza.canal) == ("SUS-1", "FIR-1", "web")
    assert poliza.emitida and poliza.emitida_en == INSTANTE
    assert poliza.id_decision == "DEC-1"
    assert auditoria.emitidas == [poliza.numero]
    assert servicio.del_cliente(CLIENTE) == (poliza,)


def test_emitir_otra_vez_la_misma_suscripcion_devuelve_la_misma_poliza(servicio, auditoria):
    primera, _ = servicio.emitir(SOLICITUD)

    repetida, nueva = servicio.emitir(SOLICITUD)

    assert not nueva
    assert repetida == primera
    assert auditoria.emitidas == [primera.numero]


def test_emisiones_simultaneas_dejan_una_sola_poliza(servicio, auditoria):
    with ThreadPoolExecutor(8) as hilos:
        polizas = list(hilos.map(lambda _: servicio.emitir(SOLICITUD)[0], range(8)))

    assert len({p.numero for p in polizas}) == 1
    assert len(servicio.del_cliente(CLIENTE)) == 1
    assert len(auditoria.emitidas) == 1


def test_el_documento_es_un_pdf_con_el_numero_y_las_coberturas(servicio):
    poliza, _ = servicio.emitir(SOLICITUD)

    _, contenido = servicio.documento(poliza.numero, CLIENTE)

    assert contenido.startswith(b"%PDF-1.4")
    assert contenido.rstrip().endswith(b"%%EOF")
    assert poliza.numero.encode() in contenido
    # WinAnsi: las tildes quedan como un byte, no como "?".
    assert "Pérdida total por daños".encode("cp1252") in contenido
    assert b"startxref" in contenido


def test_el_documento_de_otro_cliente_no_existe(servicio):
    poliza, _ = servicio.emitir(SOLICITUD)

    with pytest.raises(PolizaNoEncontradaError):
        servicio.documento(poliza.numero, "CLI-OTRO")


def test_una_poliza_de_semilla_no_tiene_documento(servicio):
    servicio._repositorio.guardar(
        Poliza("POL-STG-1", CLIENTE, "auto", date(2026, 1, 1), date(2026, 12, 31))
    )

    with pytest.raises(DocumentoNoDisponibleError):
        servicio.documento("POL-STG-1", CLIENTE)


def test_sin_almacen_ni_generador_no_se_emite():
    servicio = ServicioDePolizas(RepositorioEnMemoria(), RelojFijo(INSTANTE))

    with pytest.raises(RuntimeError):
        servicio.emitir(SOLICITUD)


@pytest.mark.parametrize(
    ("inicio", "fin"),
    [
        (date(2026, 11, 8), date(2027, 11, 7)),
        (date(2026, 1, 1), date(2026, 12, 31)),
        (date(2028, 2, 29), date(2029, 2, 28)),
    ],
)
def test_la_vigencia_anual_incluye_los_dos_extremos(inicio, fin):
    assert vigencia_anual(inicio) == fin


# -- almacén en Cloud Storage, con un cliente simulado -----------------------------------


class _GcsError(Exception):
    def __init__(self, code: int) -> None:
        super().__init__(code)
        self.code = code


class _Objeto:
    def __init__(self, bucket, nombre) -> None:
        self._bucket, self._nombre = bucket, nombre

    def upload_from_string(self, contenido, content_type, if_generation_match):
        if self._bucket.falla:
            raise _GcsError(503)
        if self._nombre in self._bucket.objetos:
            raise _GcsError(412)
        self._bucket.objetos[self._nombre] = (contenido, content_type)

    def download_as_bytes(self):
        return self._bucket.objetos[self._nombre][0]


class _Bucket:
    def __init__(self) -> None:
        self.objetos: dict[str, tuple[bytes, str]] = {}
        self.falla = False

    def blob(self, nombre):
        return _Objeto(self, nombre)

    def get_blob(self, nombre):
        if self.falla:
            raise _GcsError(503)
        return _Objeto(self, nombre) if nombre in self.objetos else None


class _ClienteGcs:
    def __init__(self) -> None:
        self.bucket_simulado = _Bucket()

    def bucket(self, _nombre):
        return self.bucket_simulado


def test_el_almacen_gcs_guarda_el_pdf_sin_sobrescribirlo():
    cliente = _ClienteGcs()
    almacen = AlmacenDeDocumentosGcs("solventa-documentos", cliente)

    id_documento = almacen.guardar("POL-2026-000001", b"%PDF-1")
    otra_vez = almacen.guardar("POL-2026-000001", b"%PDF-2")

    assert id_documento == otra_vez == "polizas/POL-2026-000001.pdf"
    assert cliente.bucket_simulado.objetos[id_documento] == (b"%PDF-1", "application/pdf")
    assert almacen.leer(id_documento) == b"%PDF-1"
    assert almacen.leer("polizas/NO-EXISTE.pdf") is None


def test_el_almacen_gcs_caido_es_dependencia_no_disponible():
    cliente = _ClienteGcs()
    almacen = AlmacenDeDocumentosGcs("solventa-documentos", cliente)
    cliente.bucket_simulado.falla = True

    with pytest.raises(AlmacenDeDocumentosNoDisponibleError):
        almacen.guardar("POL-2026-000001", b"%PDF")
    with pytest.raises(AlmacenDeDocumentosNoDisponibleError):
        almacen.leer("polizas/POL-2026-000001.pdf")


def test_el_nombre_del_objeto_no_admite_rutas():
    with pytest.raises(ValueError):
        nombre_del_objeto("../otra")


@pytest.mark.parametrize(
    ("entorno", "tipo"),
    [
        ({}, AlmacenDeDocumentosEnMemoria),
        ({"ALMACENAMIENTO_DOCUMENTOS": "gcs", "DOCUMENTOS_BUCKET": "b"}, None),
    ],
)
def test_el_almacen_se_elige_por_entorno(entorno, tipo, monkeypatch):
    if tipo is None:
        monkeypatch.setattr(dependencias, "AlmacenDeDocumentosGcs", lambda bucket: ("gcs", bucket))
        assert dependencias.crear_almacen_de_documentos(entorno) == ("gcs", "b")
    else:
        assert isinstance(dependencias.crear_almacen_de_documentos(entorno), tipo)


@pytest.mark.parametrize(
    "entorno",
    [{"ALMACENAMIENTO_DOCUMENTOS": "gcs"}, {"ALMACENAMIENTO_DOCUMENTOS": "disco"}],
)
def test_un_almacen_mal_configurado_no_arranca(entorno):
    with pytest.raises(RuntimeError):
        dependencias.crear_almacen_de_documentos(entorno)


# -- contrato HTTP del documento -------------------------------------------------------


@pytest.fixture
def nucleo(monkeypatch) -> TestClient:
    monkeypatch.delenv(dependencias.VARIABLE_SEMILLA, raising=False)
    monkeypatch.delenv(dependencias.VARIABLE_DSN, raising=False)
    monkeypatch.delenv(dependencias.VARIABLE_ALMACENAMIENTO, raising=False)
    dependencias.reiniciar()
    yield TestClient(crear_app())
    dependencias.reiniciar()


def test_el_documento_se_descarga_como_pdf(nucleo):
    poliza, _ = dependencias.obtener_servicio().emitir(SOLICITUD)

    respuesta = nucleo.get(f"/v1/polizas/clientes/{CLIENTE}/polizas/{poliza.numero}/documento")

    assert respuesta.status_code == 200
    assert respuesta.headers["content-type"] == "application/pdf"
    assert f'filename="{poliza.numero}.pdf"' in respuesta.headers["content-disposition"]
    assert respuesta.content.startswith(b"%PDF")


def test_el_documento_de_una_poliza_ajena_es_404(nucleo):
    poliza, _ = dependencias.obtener_servicio().emitir(SOLICITUD)

    respuesta = nucleo.get(f"/v1/polizas/clientes/CLI-OTRO/polizas/{poliza.numero}/documento")

    assert respuesta.status_code == 404
    assert respuesta.json()["title"] == "poliza_no_encontrada"


def test_salud_reporta_el_almacen_de_documentos(nucleo):
    dependencias_polizas = nucleo.get("/salud").json()["modulos"]["polizas"]["dependencias"]

    assert dependencias_polizas["documentos_polizas"]["estado"] == "simulado"
    assert dependencias_polizas["repositorio_polizas"]["estado"] == "simulado"
