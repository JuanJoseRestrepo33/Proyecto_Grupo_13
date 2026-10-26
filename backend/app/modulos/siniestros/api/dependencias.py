"""Composición del módulo de siniestros.

El reporte valida la vigencia contra el módulo de pólizas por su interfaz pública. Los
siniestros viven en memoria (desarrollo y pruebas).

El almacén de evidencias se elige por `ALMACENAMIENTO_EVIDENCIAS`: `gcs` usa Cloud Storage con el
bucket de `EVIDENCIAS_BUCKET`; `local` (por defecto) usa memoria, que es lo que usan el
desarrollo local y las pruebas.
"""

from __future__ import annotations

import os
from collections.abc import Mapping

from app.compartido.modulo import EstadoDeDependencia
from app.compartido.reloj import Reloj, RelojDelSistema
from app.compartido.unica import instancia_unica
from app.modulos import polizas

from ..aplicacion.servicio import ServicioDeSiniestros
from ..dominio.almacen_evidencias import AlmacenDeEvidencias
from ..infraestructura.almacen_evidencias_memoria import AlmacenDeEvidenciasEnMemoria
from ..infraestructura.polizas_nucleo import PolizasDelNucleo
from ..infraestructura.repositorio_memoria import RepositorioEnMemoria

VARIABLE_ALMACENAMIENTO = "ALMACENAMIENTO_EVIDENCIAS"
VARIABLE_BUCKET = "EVIDENCIAS_BUCKET"


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


@instancia_unica
def _componentes() -> tuple[ServicioDeSiniestros, Reloj]:
    reloj = RelojDelSistema()
    servicio = ServicioDeSiniestros(
        repositorio=RepositorioEnMemoria(),
        polizas=PolizasDelNucleo(polizas.obtener_servicio()),
        reloj=reloj,
    )
    return servicio, reloj


def obtener_servicio() -> ServicioDeSiniestros:
    return _componentes()[0]


def obtener_reloj() -> Reloj:
    return _componentes()[1]


def reiniciar() -> None:
    """Descarta la composición actual. Solo para pruebas."""
    _componentes.cache_clear()


def estado_dependencias() -> tuple[EstadoDeDependencia, ...]:
    return (
        EstadoDeDependencia("repositorio_siniestros", "simulado", "En memoria"),
        EstadoDeDependencia("polizas", "ok", "Módulo de pólizas del núcleo (interfaz pública)"),
    )
