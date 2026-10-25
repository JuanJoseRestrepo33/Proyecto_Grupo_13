"""Composición del módulo de siniestros.

El almacén de evidencias se elige por `ALMACENAMIENTO_EVIDENCIAS`: `gcs` usa Cloud Storage con el
bucket de `EVIDENCIAS_BUCKET`; `local` (por defecto) usa memoria, que es lo que usan el
desarrollo local y las pruebas.
"""

from __future__ import annotations

import os
from collections.abc import Mapping

from ..dominio.almacen_evidencias import AlmacenDeEvidencias
from ..infraestructura.almacen_evidencias_memoria import AlmacenDeEvidenciasEnMemoria

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
