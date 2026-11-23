"""Almacén de evidencias en Cloud Storage (MOB-F05, #177).

El bucket es privado: acceso uniforme, acceso público bloqueado y ningún objeto con URL pública.
El núcleo se autentica con su cuenta de servicio (credenciales por defecto de Cloud Run), sin
llaves JSON, y esa cuenta solo puede crear y leer objetos del bucket: no puede borrarlos ni
sobrescribirlos. Además, cada subida exige que el objeto no exista (`if_generation_match=0`), así
que una evidencia nunca se reemplaza, ni siquiera con permisos de más.

`google-cloud-storage` es una dependencia opcional (extra `gcs`), por eso se importa solo al crear
el cliente real; las pruebas inyectan uno simulado.
"""

from __future__ import annotations

from typing import Any

from ..dominio.almacen_evidencias import (
    AlmacenamientoNoDisponibleError,
    ArchivoDeEvidencia,
    Evidencia,
    EvidenciaDuplicadaError,
    limpiar_nombre_original,
    nombre_del_objeto,
)

# Cloud Storage responde 412 cuando falla la precondición `if_generation_match=0`: el objeto existe.
_PRECONDICION_FALLIDA = 412
_METADATO_NOMBRE = "nombre_original"


def _codigo(error: Exception) -> int | None:
    return getattr(error, "code", None)


class AlmacenDeEvidenciasGcs:
    def __init__(self, bucket: str, cliente: Any | None = None) -> None:
        if cliente is None:  # pragma: no cover - requiere el extra `gcs` y credenciales reales
            from google.cloud import storage

            cliente = storage.Client()
        self._bucket = cliente.bucket(bucket)

    def guardar(
        self,
        siniestro_id: str,
        evidencia_id: str,
        tipo_contenido: str,
        contenido: bytes,
        nombre_original: str | None = None,
    ) -> Evidencia:
        nombre = nombre_del_objeto(siniestro_id, evidencia_id)
        original = limpiar_nombre_original(nombre_original)
        objeto = self._bucket.blob(nombre)
        if original:
            objeto.metadata = {_METADATO_NOMBRE: original}
        try:
            objeto.upload_from_string(contenido, content_type=tipo_contenido, if_generation_match=0)
        except Exception as error:
            if _codigo(error) == _PRECONDICION_FALLIDA:
                raise EvidenciaDuplicadaError(nombre) from error
            raise AlmacenamientoNoDisponibleError(f"No se pudo guardar {nombre}") from error
        return Evidencia(siniestro_id, evidencia_id, tipo_contenido, len(contenido), original)

    def leer(self, siniestro_id: str, evidencia_id: str) -> ArchivoDeEvidencia | None:
        nombre = nombre_del_objeto(siniestro_id, evidencia_id)
        try:
            objeto = self._bucket.get_blob(nombre)
            if objeto is None:
                return None
            contenido = objeto.download_as_bytes()
        except Exception as error:
            raise AlmacenamientoNoDisponibleError(f"No se pudo leer {nombre}") from error
        metadatos = objeto.metadata or {}
        evidencia = Evidencia(
            siniestro_id,
            evidencia_id,
            objeto.content_type or "application/octet-stream",
            len(contenido),
            metadatos.get(_METADATO_NOMBRE),
        )
        return ArchivoDeEvidencia(evidencia, contenido)
