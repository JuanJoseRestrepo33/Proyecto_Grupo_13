"""Composición del módulo de siniestros.

El reporte valida la vigencia contra el módulo de pólizas por su interfaz pública. Con
`SINIESTROS_DATABASE_URL` los siniestros viven en PostgreSQL, en el esquema propio del
módulo; sin ella, en memoria (desarrollo y pruebas).

El almacén de evidencias se elige por `ALMACENAMIENTO_EVIDENCIAS`: `gcs` usa Cloud Storage con el
bucket de `EVIDENCIAS_BUCKET`; `local` (por defecto) usa memoria, que es lo que usan el
desarrollo local y las pruebas.
"""

from __future__ import annotations

import logging
import os
from collections.abc import Mapping

from app.compartido.eventos import obtener_bus
from app.compartido.modulo import EstadoDeDependencia
from app.compartido.reloj import Reloj, RelojDelSistema
from app.compartido.unica import instancia_unica
from app.modulos import auditoria, polizas

from ..aplicacion.servicio import ServicioDeSiniestros
from ..dominio.almacen_evidencias import AlmacenDeEvidencias
from ..dominio.puertos import RepositorioSiniestros
from ..infraestructura.almacen_evidencias_memoria import AlmacenDeEvidenciasEnMemoria
from ..infraestructura.auditoria_nucleo import RegistroEnAuditoria
from ..infraestructura.polizas_nucleo import PolizasDelNucleo
from ..infraestructura.repositorio_memoria import RepositorioEnMemoria

VARIABLE_ALMACENAMIENTO = "ALMACENAMIENTO_EVIDENCIAS"
VARIABLE_BUCKET = "EVIDENCIAS_BUCKET"
VARIABLE_DSN = "SINIESTROS_DATABASE_URL"

log = logging.getLogger(__name__)


def crear_almacen_de_evidencias(entorno: Mapping[str, str] | None = None) -> AlmacenDeEvidencias:
    entorno = os.environ if entorno is None else entorno
    tipo = entorno.get(VARIABLE_ALMACENAMIENTO, "local")
    if tipo == "local":
        return AlmacenDeEvidenciasEnMemoria()
    if tipo == "gcs":
        bucket = entorno.get(VARIABLE_BUCKET)
        if not bucket:
            # Sin bucket no se arranca en silencio contra memoria: la evidencia se perdería.
            raise RuntimeError(f"{VARIABLE_ALMACENAMIENTO}=gcs requiere {VARIABLE_BUCKET}")
        from ..infraestructura.almacen_evidencias_gcs import AlmacenDeEvidenciasGcs

        return AlmacenDeEvidenciasGcs(bucket)
    raise RuntimeError(f"{VARIABLE_ALMACENAMIENTO} no admite {tipo!r}: usa 'local' o 'gcs'")


def _crear_repositorio() -> RepositorioSiniestros:
    dsn = os.environ.get(VARIABLE_DSN)
    if not dsn:
        return RepositorioEnMemoria()
    from ..infraestructura.repositorio_postgres import RepositorioPostgres

    repositorio = RepositorioPostgres(dsn)
    try:
        repositorio.preparar()
    except Exception:  # noqa: BLE001 - la base caída al arrancar no debe tumbar el núcleo
        # No se cae a memoria en silencio: un reporte que se pierde con la siguiente revisión
        # rompe la promesa de la referencia. `/salud` lo reporta y se reintenta al usarlo.
        log.exception("No se pudo preparar el esquema de siniestros; se reintentará")
    return repositorio


@instancia_unica
def _componentes() -> tuple[ServicioDeSiniestros, Reloj, RepositorioSiniestros]:
    reloj = RelojDelSistema()
    repositorio = _crear_repositorio()
    servicio = ServicioDeSiniestros(
        repositorio=repositorio,
        polizas=PolizasDelNucleo(polizas.obtener_servicio()),
        auditoria=RegistroEnAuditoria(auditoria.obtener_servicio()),
        bus=obtener_bus(),
        reloj=reloj,
        almacen=crear_almacen_de_evidencias(),
    )
    return servicio, reloj, repositorio


def obtener_servicio() -> ServicioDeSiniestros:
    return _componentes()[0]


def obtener_reloj() -> Reloj:
    return _componentes()[1]


def reiniciar() -> None:
    """Descarta la composición actual. Solo para pruebas."""
    _componentes.cache_clear()


def _estado_repositorio() -> EstadoDeDependencia:
    repositorio = _componentes()[2]
    if isinstance(repositorio, RepositorioEnMemoria):
        return EstadoDeDependencia(
            "repositorio_siniestros", "simulado", f"En memoria; define {VARIABLE_DSN}"
        )
    try:
        repositorio.verificar_conexion()  # type: ignore[attr-defined]
    except Exception as error:  # noqa: BLE001 - cualquier fallo de conexión es "caído"
        return EstadoDeDependencia("repositorio_siniestros", "caido", type(error).__name__)
    return EstadoDeDependencia("repositorio_siniestros", "ok", "PostgreSQL, esquema siniestros")


def _estado_almacen() -> EstadoDeDependencia:
    tipo = os.environ.get(VARIABLE_ALMACENAMIENTO, "local")
    if tipo == "gcs":
        bucket = os.environ.get(VARIABLE_BUCKET, "")
        return EstadoDeDependencia("almacen_evidencias", "ok", f"Cloud Storage, bucket {bucket}")
    return EstadoDeDependencia("almacen_evidencias", "simulado", "En memoria")


def estado_dependencias() -> tuple[EstadoDeDependencia, ...]:
    return (
        _estado_repositorio(),
        EstadoDeDependencia("polizas", "ok", "Módulo de pólizas del núcleo (interfaz pública)"),
        EstadoDeDependencia("auditoria", "ok", "Módulo de auditoría del núcleo (ARQ-03)"),
        _estado_almacen(),
    )
