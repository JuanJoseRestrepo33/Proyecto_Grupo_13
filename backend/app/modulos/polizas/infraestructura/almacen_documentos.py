"""Almacenes del documento de la póliza: memoria (desarrollo y pruebas) y Cloud Storage.

Igual que las evidencias (MOB-F05): el objeto se llama `polizas/{numero}.pdf`, no revela nada
del cliente, y nunca se sobrescribe (`if_generation_match=0`). El bucket es privado; el
documento sale por el BFF con la sesión del cliente.

`google-cloud-storage` es una dependencia opcional (extra `gcs`), por eso se importa solo al
crear el cliente real; las pruebas inyectan uno simulado.
"""

from __future__ import annotations

import re
import threading
from typing import Any

from ..dominio.errores import AlmacenDeDocumentosNoDisponibleError

_NUMERO = re.compile(r"[A-Za-z0-9_-]{1,64}")
_PRECONDICION_FALLIDA = 412
TIPO_PDF = "application/pdf"


def nombre_del_objeto(numero_poliza: str) -> str:
    if not _NUMERO.fullmatch(numero_poliza):
        raise ValueError(f"Número de póliza no válido para el almacén: {numero_poliza!r}")
    return f"polizas/{numero_poliza}.pdf"


class AlmacenDeDocumentosEnMemoria:
    def __init__(self) -> None:
        self._documentos: dict[str, bytes] = {}
        self._cerrojo = threading.Lock()

    def guardar(self, numero_poliza: str, contenido: bytes) -> str:
        nombre = nombre_del_objeto(numero_poliza)
        with self._cerrojo:
            self._documentos.setdefault(nombre, contenido)
        return nombre

    def leer(self, id_documento: str) -> bytes | None:
        with self._cerrojo:
            return self._documentos.get(id_documento)


class AlmacenDeDocumentosGcs:
    def __init__(self, bucket: str, cliente: Any | None = None) -> None:
        if cliente is None:  # pragma: no cover - requiere el extra `gcs` y credenciales reales
            from google.cloud import storage

            cliente = storage.Client()
        self._bucket = cliente.bucket(bucket)

    def guardar(self, numero_poliza: str, contenido: bytes) -> str:
        nombre = nombre_del_objeto(numero_poliza)
        try:
            self._bucket.blob(nombre).upload_from_string(
                contenido, content_type=TIPO_PDF, if_generation_match=0
            )
        except Exception as error:
            # Ya existía: el número de póliza es único, así que es el mismo documento.
            if getattr(error, "code", None) != _PRECONDICION_FALLIDA:
                raise AlmacenDeDocumentosNoDisponibleError(
                    f"No se pudo guardar {nombre}"
                ) from error
        return nombre

    def leer(self, id_documento: str) -> bytes | None:
        try:
            objeto = self._bucket.get_blob(id_documento)
            return None if objeto is None else objeto.download_as_bytes()
        except Exception as error:
            raise AlmacenDeDocumentosNoDisponibleError(f"No se pudo leer {id_documento}") from error
