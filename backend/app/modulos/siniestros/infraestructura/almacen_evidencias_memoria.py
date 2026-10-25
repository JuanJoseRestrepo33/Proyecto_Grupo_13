"""Almacén de evidencias en memoria, para desarrollo local y pruebas.

Cumple las mismas reglas que el de Cloud Storage: no borra ni reemplaza. Lo que guarda se pierde al
reiniciar el proceso.
"""

from __future__ import annotations

from threading import Lock

from ..dominio.almacen_evidencias import (
    ArchivoDeEvidencia,
    Evidencia,
    EvidenciaDuplicadaError,
    limpiar_nombre_original,
    nombre_del_objeto,
)


class AlmacenDeEvidenciasEnMemoria:
    def __init__(self) -> None:
        self._archivos: dict[str, ArchivoDeEvidencia] = {}
        self._cerrojo = Lock()

    def guardar(
        self,
        siniestro_id: str,
        evidencia_id: str,
        tipo_contenido: str,
        contenido: bytes,
        nombre_original: str | None = None,
    ) -> Evidencia:
        nombre = nombre_del_objeto(siniestro_id, evidencia_id)
        evidencia = Evidencia(
            siniestro_id,
            evidencia_id,
            tipo_contenido,
            len(contenido),
            limpiar_nombre_original(nombre_original),
        )
        with self._cerrojo:
            if nombre in self._archivos:
                raise EvidenciaDuplicadaError(nombre)
            self._archivos[nombre] = ArchivoDeEvidencia(evidencia, bytes(contenido))
        return evidencia

    def leer(self, siniestro_id: str, evidencia_id: str) -> ArchivoDeEvidencia | None:
        return self._archivos.get(nombre_del_objeto(siniestro_id, evidencia_id))
