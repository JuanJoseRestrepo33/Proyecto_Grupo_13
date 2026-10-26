"""Evidencia asociada al siniestro sobre el puerto de almacenamiento (MOB-F05)."""

from __future__ import annotations

from dataclasses import replace
from datetime import UTC, datetime, timedelta

import pytest

from app.compartido.eventos import BusEnProceso
from app.compartido.reloj import RelojFijo
from app.modulos.auditoria.aplicacion.servicio import ServicioDeAuditoria
from app.modulos.auditoria.infraestructura.almacen_memoria import AlmacenEnMemoria
from app.modulos.siniestros.aplicacion.servicio import ServicioDeSiniestros
from app.modulos.siniestros.dominio.almacen_evidencias import AlmacenamientoNoDisponibleError
from app.modulos.siniestros.dominio.errores import (
    EvidenciaNoEncontradaError,
    SiniestroCerradoError,
    SiniestroNoEncontradoError,
)
from app.modulos.siniestros.dominio.evidencias import (
    ArchivoDemasiadoGrandeError,
    FormatoNoAdmitidoError,
)
from app.modulos.siniestros.dominio.modelo import DatosDeReporte, EstadoSiniestro, TipoEvento
from app.modulos.siniestros.dominio.puertos import VigenciaDePoliza
from app.modulos.siniestros.infraestructura.almacen_evidencias_memoria import (
    AlmacenDeEvidenciasEnMemoria,
)
from app.modulos.siniestros.infraestructura.auditoria_nucleo import RegistroEnAuditoria
from app.modulos.siniestros.infraestructura.repositorio_memoria import RepositorioEnMemoria

INSTANTE = datetime(2026, 10, 26, 15, 0, tzinfo=UTC)
CLIENTE = "CLI-2026-000318"
FOTO = b"\xff\xd8\xff\xe0" + b"foto" * 50
PDF = b"%PDF-1.7\n" + b"documento" * 20
POLIZA = VigenciaDePoliza(
    "POL-1", "auto", INSTANTE.date() - timedelta(days=30), INSTANTE.date() + timedelta(days=30)
)
DATOS = DatosDeReporte(
    numero_poliza="POL-1",
    tipo_evento=TipoEvento.ACCIDENTE_VEHICULO,
    ocurrido_el=INSTANTE.date() - timedelta(days=1),
    descripcion="Choque leve en la calle 80 al frenar.",
)


class Polizas:
    def poliza_del_cliente(self, numero, id_cliente):
        return POLIZA if id_cliente == CLIENTE else None


class AlmacenQueCuenta(AlmacenDeEvidenciasEnMemoria):
    def __init__(self) -> None:
        super().__init__()
        self.guardados = 0

    def guardar(self, *args, **kwargs):
        self.guardados += 1
        return super().guardar(*args, **kwargs)


class AlmacenCaido(AlmacenDeEvidenciasEnMemoria):
    def guardar(self, *args, **kwargs):
        raise AlmacenamientoNoDisponibleError("sin almacén")


@pytest.fixture
def almacen() -> AlmacenQueCuenta:
    return AlmacenQueCuenta()


@pytest.fixture
def auditoria() -> AlmacenEnMemoria:
    return AlmacenEnMemoria()


@pytest.fixture
def repositorio() -> RepositorioEnMemoria:
    return RepositorioEnMemoria()


@pytest.fixture
def reloj() -> RelojFijo:
    return RelojFijo(INSTANTE)


@pytest.fixture
def servicio(repositorio, almacen, auditoria, reloj) -> ServicioDeSiniestros:
    return ServicioDeSiniestros(
        repositorio=repositorio,
        polizas=Polizas(),
        auditoria=RegistroEnAuditoria(ServicioDeAuditoria(auditoria)),
        bus=BusEnProceso(),
        reloj=reloj,
        almacen=almacen,
    )


@pytest.fixture
def siniestro(servicio):
    return servicio.reportar(CLIENTE, DATOS, "web", "clave-evid")[0]


def test_la_evidencia_queda_en_el_almacen_y_asociada_al_siniestro(servicio, siniestro, almacen):
    evidencia = servicio.adjuntar_evidencia(
        CLIENTE, siniestro.id_siniestro, "image/jpeg", FOTO, "C:\\fotos\\choque.jpg", "movil"
    )

    assert evidencia.id_evidencia.startswith("EVI-")
    assert evidencia.tamano == len(FOTO)
    assert evidencia.nombre_original == "choque.jpg"
    assert evidencia.canal == "movil"
    assert evidencia.cargada_en == INSTANTE
    guardado = almacen.leer(siniestro.id_siniestro, evidencia.id_evidencia)
    assert guardado is not None and guardado.contenido == FOTO


def test_la_evidencia_agregada_en_un_canal_se_ve_en_el_otro(servicio, siniestro, reloj):
    """Criterio 5: el mismo siniestro, desde la app y desde la web."""
    desde_app = servicio.adjuntar_evidencia(
        CLIENTE, siniestro.id_siniestro, "image/jpeg", FOTO, "choque.jpg", "movil"
    )
    reloj.avanzar(60)
    desde_web = servicio.adjuntar_evidencia(
        CLIENTE, siniestro.id_siniestro, "application/pdf", PDF, "factura.pdf", "web"
    )

    assert servicio.evidencias(CLIENTE, siniestro.id_siniestro) == (desde_app, desde_web)


def test_se_puede_descargar_la_evidencia_del_propio_siniestro(servicio, siniestro):
    evidencia = servicio.adjuntar_evidencia(
        CLIENTE, siniestro.id_siniestro, "application/pdf", PDF, None, "web"
    )

    leida, contenido = servicio.archivo_de_evidencia(
        CLIENTE, siniestro.id_siniestro, evidencia.id_evidencia
    )

    assert leida == evidencia
    assert contenido == PDF


def test_la_evidencia_de_otro_cliente_no_existe_para_este(servicio, siniestro):
    evidencia = servicio.adjuntar_evidencia(
        CLIENTE, siniestro.id_siniestro, "image/jpeg", FOTO, None, "web"
    )

    with pytest.raises(SiniestroNoEncontradoError):
        servicio.archivo_de_evidencia("CLI-OTRO", siniestro.id_siniestro, evidencia.id_evidencia)
    with pytest.raises(SiniestroNoEncontradoError):
        servicio.adjuntar_evidencia(
            "CLI-OTRO", siniestro.id_siniestro, "image/jpeg", FOTO, None, "web"
        )
    with pytest.raises(EvidenciaNoEncontradaError):
        servicio.archivo_de_evidencia(CLIENTE, siniestro.id_siniestro, "EVI-nada")


def test_lo_que_no_cumple_los_limites_no_llega_al_almacen(
    servicio, siniestro, almacen, repositorio
):
    with pytest.raises(FormatoNoAdmitidoError):
        servicio.adjuntar_evidencia(
            CLIENTE, siniestro.id_siniestro, "image/jpeg", b"MZ ejecutable", "virus.jpg", "web"
        )
    with pytest.raises(ArchivoDemasiadoGrandeError):
        servicio.adjuntar_evidencia(
            CLIENTE,
            siniestro.id_siniestro,
            "image/jpeg",
            FOTO + b"0" * 10 * 1024 * 1024,
            None,
            "web",
        )

    assert repositorio.evidencias(siniestro.id_siniestro) == ()
    assert almacen.guardados == 0


def test_un_siniestro_cerrado_no_recibe_evidencia(servicio, siniestro, repositorio, monkeypatch):
    cerrado = replace(siniestro, estado=EstadoSiniestro.PAGADO)
    monkeypatch.setattr(repositorio, "obtener", lambda id_cliente, id_siniestro: cerrado)

    with pytest.raises(SiniestroCerradoError):
        servicio.adjuntar_evidencia(
            CLIENTE, siniestro.id_siniestro, "image/jpeg", FOTO, None, "web"
        )


def test_con_el_almacen_caido_no_se_asocia_nada(repositorio, auditoria, reloj):
    servicio = ServicioDeSiniestros(
        repositorio=repositorio,
        polizas=Polizas(),
        auditoria=RegistroEnAuditoria(ServicioDeAuditoria(auditoria)),
        bus=BusEnProceso(),
        reloj=reloj,
        almacen=AlmacenCaido(),
    )
    siniestro, _ = servicio.reportar(CLIENTE, DATOS, "web")

    with pytest.raises(AlmacenamientoNoDisponibleError):
        servicio.adjuntar_evidencia(
            CLIENTE, siniestro.id_siniestro, "image/jpeg", FOTO, None, "web"
        )

    assert repositorio.evidencias(siniestro.id_siniestro) == ()


def test_la_evidencia_queda_auditada_sin_el_nombre_del_archivo(servicio, siniestro, auditoria):
    evidencia = servicio.adjuntar_evidencia(
        CLIENTE, siniestro.id_siniestro, "image/jpeg", FOTO, "cedula-de-ana.jpg", "movil"
    )

    registro = auditoria.todos()[-1].evento
    assert registro.tipo == "evidencia_agregada"
    assert registro.canal == "movil"
    assert registro.resultado == {"id_evidencia": evidencia.id_evidencia}
    assert "cedula-de-ana" not in str(registro)
