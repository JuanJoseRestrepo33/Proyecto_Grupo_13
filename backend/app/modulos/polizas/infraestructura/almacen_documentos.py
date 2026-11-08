"""Almacenes del documento de la póliza: memoria (desarrollo y pruebas) y Cloud Storage.

Igual que las evidencias (MOB-F05): el objeto se llama `polizas/{id_suscripcion}/{numero}.pdf`,
no revela nada del cliente, y nunca se sobrescribe (`if_generation_match=0`). El bucket es
privado; el documento sale por el BFF con la sesión del cliente.

La ruta lleva el id de la suscripción (un UUID) y no solo el número: el número que ve el
cliente no es único para siempre (el repositorio en memoria vuelve a empezar al reiniciar, y
una base recreada también), y si dos pólizas compartieran objeto una apuntaría al PDF de la
otra. Con la suscripción en la ruta, un objeto que ya existe solo puede ser el de la misma
póliza.

`google-cloud-storage` es una dependencia opcional (extra `gcs`), por eso se importa solo al
crear el cliente real; las pruebas inyectan uno simulado.
"""

from __future__ import annotations

import re
import threading
from typing import Any

from ..dominio.errores import AlmacenDeDocumentosNoDisponibleError

_SEGMENTO = re.compile(r"[A-Za-z0-9_-]{1,64}")
_PRECONDICION_FALLIDA = 412
_NO_EXISTE = 404
TIPO_PDF = "application/pdf"


def nombre_del_objeto(numero_poliza: str, id_suscripcion: str) -> str:
    for valor in (numero_poliza, id_suscripcion):
        if not _SEGMENTO.fullmatch(valor):
            raise ValueError(f"Identificador no válido para el almacén: {valor!r}")
    return f"polizas/{id_suscripcion}/{numero_poliza}.pdf"


class AlmacenDeDocumentosEnMemoria:
    def __init__(self) -> None:
        self._documentos: dict[str, bytes] = {}
        self._cerrojo = threading.Lock()

    def guardar(self, numero_poliza: str, id_suscripcion: str, contenido: bytes) -> str:
        nombre = nombre_del_objeto(numero_poliza, id_suscripcion)
        with self._cerrojo:
            self._documentos.setdefault(nombre, contenido)
        return nombre

    def leer(self, id_documento: str) -> bytes | None:
        with self._cerrojo:
            return self._documentos.get(id_documento)

    def borrar(self, id_documento: str) -> None:
        with self._cerrojo:
            self._documentos.pop(id_documento, None)


class AlmacenDeDocumentosGcs:
    def __init__(self, bucket: str, cliente: Any | None = None) -> None:
        if cliente is None:  # pragma: no cover - requiere el extra `gcs` y credenciales reales
            from google.cloud import storage

            cliente = storage.Client()
        self._bucket = cliente.bucket(bucket)

    def guardar(self, numero_poliza: str, id_suscripcion: str, contenido: bytes) -> str:
        nombre = nombre_del_objeto(numero_poliza, id_suscripcion)
        try:
            self._bucket.blob(nombre).upload_from_string(
                contenido, content_type=TIPO_PDF, if_generation_match=0
            )
        except Exception as error:
            # Ya existía: la ruta es de esta póliza y esta suscripción, es el mismo documento.
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

    def borrar(self, id_documento: str) -> None:
        try:
            self._bucket.blob(id_documento).delete()
        except Exception as error:
            if getattr(error, "code", None) != _NO_EXISTE:
                raise AlmacenDeDocumentosNoDisponibleError(
                    f"No se pudo borrar {id_documento}"
                ) from error
