"""Puerto de almacenamiento de las evidencias de un siniestro (MOB-F05).

La evidencia (fotos, videos y documentos) se guarda fuera de la base de datos, detrás de este
puerto: en memoria para desarrollo y pruebas, en Cloud Storage en *staging* y producción.

Reglas que cumple toda implementación:

- **No se borra ni se reemplaza.** No existe operación de borrar, y guardar dos veces la misma
  evidencia falla con `EvidenciaDuplicadaError` en lugar de sobrescribirla. En Cloud Storage,
  además, la cuenta del núcleo no tiene permiso de borrado.
- **El nombre del objeto no revela nada del cliente:** `siniestros/{siniestro_id}/{evidencia_id}`.
  El nombre original del archivo viaja como metadato.
- **Los límites de negocio (10 MB, formatos admitidos) se validan antes de llamar al puerto.** El
  almacén solo guarda lo que recibe.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Protocol

from app.compartido.errores import DependenciaNoDisponible, ReglaDeNegocioViolada

# Identificadores aptos para el nombre del objeto: sin "/", "..", espacios ni datos del cliente.
_IDENTIFICADOR = re.compile(r"[A-Za-z0-9_-]{1,64}")
LARGO_MAXIMO_NOMBRE_ORIGINAL = 255


class AlmacenamientoNoDisponibleError(DependenciaNoDisponible):
    """El almacén no respondió o rechazó la operación por una causa ajena a la evidencia."""

    codigo = "almacenamiento_no_disponible"


class EvidenciaDuplicadaError(ReglaDeNegocioViolada):
    """Ya existe una evidencia con ese identificador en el siniestro; no se reemplaza."""

    codigo = "evidencia_duplicada"


@dataclass(frozen=True)
class Evidencia:
    siniestro_id: str
    evidencia_id: str
    tipo_contenido: str
    tamano: int
    nombre_original: str | None = None


@dataclass(frozen=True)
class ArchivoDeEvidencia:
    evidencia: Evidencia
    contenido: bytes


def nombre_del_objeto(siniestro_id: str, evidencia_id: str) -> str:
    """Ruta del objeto en el almacén; rechaza identificadores que no sean seguros para ella."""
    for valor in (siniestro_id, evidencia_id):
        if not _IDENTIFICADOR.fullmatch(valor):
            raise ValueError(f"Identificador no válido para el almacén: {valor!r}")
    return f"siniestros/{siniestro_id}/{evidencia_id}"


def limpiar_nombre_original(nombre: str | None) -> str | None:
    """Nombre del archivo tal como lo subió el cliente, sin rutas ni caracteres de control."""
    if not nombre:
        return None
    base = re.split(r"[\\/]", nombre)[-1]
    limpio = "".join(c for c in base if c.isprintable()).strip()
    return limpio[:LARGO_MAXIMO_NOMBRE_ORIGINAL] or None


class AlmacenDeEvidencias(Protocol):
    """Guarda y entrega evidencias. No existe operación de borrar ni de reemplazar."""

    def guardar(
        self,
        siniestro_id: str,
        evidencia_id: str,
        tipo_contenido: str,
        contenido: bytes,
        nombre_original: str | None = None,
    ) -> Evidencia:
        """Guarda la evidencia; `EvidenciaDuplicadaError` si ya existe, nunca la sobrescribe."""
        raise NotImplementedError

    def leer(self, siniestro_id: str, evidencia_id: str) -> ArchivoDeEvidencia | None:
        """La evidencia con su contenido, o None si no existe."""
        raise NotImplementedError
