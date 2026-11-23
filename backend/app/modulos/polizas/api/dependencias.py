"""Composición del módulo de pólizas.

- `POLIZAS_DATABASE_URL`: con ella las pólizas viven en PostgreSQL, en el esquema propio del
  módulo; sin ella, en memoria (desarrollo y pruebas).
- `POLIZAS_SEMILLA` (ruta a un JSON): carga las pólizas de prueba al arrancar. Una semilla
  ilegible no tumba el núcleo: `/salud` lo reporta.
- `ALMACENAMIENTO_DOCUMENTOS` = `local` (memoria, por defecto) o `gcs`, con el bucket en
  `DOCUMENTOS_BUCKET`: dónde queda el PDF de cada póliza emitida.
"""

from __future__ import annotations

import logging
import os
from collections.abc import Mapping

from app.compartido.modulo import EstadoDeDependencia
from app.compartido.reloj import Reloj, RelojDelSistema
from app.compartido.unica import instancia_unica
from app.modulos import auditoria

from ..aplicacion.servicio import ServicioDePolizas
from ..dominio.puertos import AlmacenDeDocumentos, RepositorioPolizas
from ..infraestructura import semilla
from ..infraestructura.almacen_documentos import (
    AlmacenDeDocumentosEnMemoria,
    AlmacenDeDocumentosGcs,
)
from ..infraestructura.auditoria_nucleo import RegistroEnAuditoria
from ..infraestructura.catalogo_nucleo import CatalogoDelNucleo
from ..infraestructura.documento_pdf import GeneradorPdf
from ..infraestructura.repositorio_memoria import RepositorioEnMemoria

VARIABLE_SEMILLA = "POLIZAS_SEMILLA"
VARIABLE_DSN = "POLIZAS_DATABASE_URL"
VARIABLE_ALMACENAMIENTO = "ALMACENAMIENTO_DOCUMENTOS"
VARIABLE_BUCKET = "DOCUMENTOS_BUCKET"

log = logging.getLogger(__name__)


def _almacen_configurado(entorno: Mapping[str, str]) -> tuple[str, str | None]:
    tipo = entorno.get(VARIABLE_ALMACENAMIENTO, "local")
    if tipo == "local":
        return tipo, None
    if tipo == "gcs":
        bucket = entorno.get(VARIABLE_BUCKET)
        if not bucket:
            # Sin bucket no se arranca en silencio contra memoria: el documento se perdería.
            raise RuntimeError(f"{VARIABLE_ALMACENAMIENTO}=gcs requiere {VARIABLE_BUCKET}")
        return tipo, bucket
    raise RuntimeError(f"{VARIABLE_ALMACENAMIENTO} no admite {tipo!r}: usa 'local' o 'gcs'")


def crear_almacen_de_documentos(entorno: Mapping[str, str] | None = None) -> AlmacenDeDocumentos:
    tipo, bucket = _almacen_configurado(os.environ if entorno is None else entorno)
    if tipo == "gcs":
        return AlmacenDeDocumentosGcs(bucket)  # type: ignore[arg-type]
    return AlmacenDeDocumentosEnMemoria()


def validar_configuracion() -> None:
    """Revisa `ALMACENAMIENTO_DOCUMENTOS` al armar el núcleo: un valor inválido impide
    arrancar, en vez de fallar en la primera solicitud (y en `/salud`)."""
    _almacen_configurado(os.environ)


def _crear_repositorio() -> RepositorioPolizas:
    dsn = os.environ.get(VARIABLE_DSN)
    if not dsn:
        return RepositorioEnMemoria()
    from ..infraestructura.repositorio_postgres import RepositorioPostgres

    repositorio = RepositorioPostgres(dsn)
    try:
        repositorio.preparar()
    except Exception:  # noqa: BLE001 - la base caída al arrancar no debe tumbar el núcleo
        log.exception("No se pudo preparar el esquema de pólizas; se reintentará")
    return repositorio


def _sembrar(
    repositorio: RepositorioPolizas, reloj: Reloj
) -> tuple[semilla.Semilla, EstadoDeDependencia]:
    ruta = os.environ.get(VARIABLE_SEMILLA)
    if not ruta:
        return semilla.Semilla(), EstadoDeDependencia(
            "semilla_polizas", "ok", "Sin pólizas de prueba"
        )
    try:
        cargada = semilla.cargar(ruta, reloj.ahora().date())
        for poliza in cargada.polizas:
            repositorio.guardar(poliza)
    except semilla.SemillaInvalidaError as error:
        log.error("Semilla de pólizas inválida: %s", error)
        return semilla.Semilla(), EstadoDeDependencia("semilla_polizas", "degradado", str(error))
    except Exception as error:  # noqa: BLE001 - la base caída al arrancar no debe tumbar el núcleo
        log.exception("No se pudo guardar la semilla de pólizas")
        return semilla.Semilla(), EstadoDeDependencia(
            "semilla_polizas", "degradado", type(error).__name__
        )
    detalle = f"{len(cargada.polizas)} pólizas y {len(cargada.plantillas)} plantillas de prueba"
    return cargada, EstadoDeDependencia("semilla_polizas", "simulado", detalle)


@instancia_unica
def _componentes() -> tuple[ServicioDePolizas, Reloj, EstadoDeDependencia, RepositorioPolizas]:
    reloj = RelojDelSistema()
    repositorio = _crear_repositorio()
    cargada, estado = _sembrar(repositorio, reloj)
    servicio = ServicioDePolizas(
        repositorio,
        reloj,
        cargada.plantillas,
        almacen=crear_almacen_de_documentos(),
        generador=GeneradorPdf(),
        auditoria=RegistroEnAuditoria(auditoria.obtener_servicio()),
        catalogo=CatalogoDelNucleo(),
    )
    return servicio, reloj, estado, repositorio


def obtener_servicio() -> ServicioDePolizas:
    return _componentes()[0]


def obtener_reloj() -> Reloj:
    return _componentes()[1]


def reiniciar() -> None:
    """Descarta la composición actual. Solo para pruebas."""
    _componentes.cache_clear()


def _estado_repositorio() -> EstadoDeDependencia:
    repositorio = _componentes()[3]
    if isinstance(repositorio, RepositorioEnMemoria):
        return EstadoDeDependencia(
            "repositorio_polizas", "simulado", f"En memoria; define {VARIABLE_DSN}"
        )
    try:
        repositorio.verificar_conexion()  # type: ignore[attr-defined]
    except Exception as error:  # noqa: BLE001 - cualquier fallo de conexión es "caído"
        return EstadoDeDependencia("repositorio_polizas", "caido", type(error).__name__)
    return EstadoDeDependencia("repositorio_polizas", "ok", "PostgreSQL, esquema polizas")


def _estado_almacen() -> EstadoDeDependencia:
    if os.environ.get(VARIABLE_ALMACENAMIENTO, "local") == "gcs":
        bucket = os.environ.get(VARIABLE_BUCKET, "")
        return EstadoDeDependencia("documentos_polizas", "ok", f"Cloud Storage, bucket {bucket}")
    return EstadoDeDependencia("documentos_polizas", "simulado", "En memoria")


def estado_dependencias() -> tuple[EstadoDeDependencia, ...]:
    return (_estado_repositorio(), _componentes()[2], _estado_almacen())
