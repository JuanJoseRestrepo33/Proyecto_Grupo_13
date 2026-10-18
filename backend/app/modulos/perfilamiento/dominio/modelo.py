"""Modelo del perfil de riesgo.

El perfil es una copia de lo que el proveedor de Open Finance respondió. `RespuestaPerfil`
dice además de dónde salió: del proveedor, de la caché (el proveedor no respondió a tiempo
o el circuito está abierto) o de ningún lado. Cotización tarifa igual en los dos primeros
casos y lo deja escrito en la explicación del precio (RC-03, RC-08).
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum


class Origen(StrEnum):
    PROVEEDOR = "proveedor"
    CACHE = "cache"
    NINGUNO = "ninguno"


@dataclass(frozen=True, slots=True)
class PerfilDeRiesgo:
    puntaje: int
    comportamiento_pago: str
    nivel_endeudamiento: str


@dataclass(frozen=True, slots=True)
class RespuestaPerfil:
    perfil: PerfilDeRiesgo | None
    origen: Origen

    @property
    def degradado(self) -> bool:
        """True cuando no vino del proveedor: es el camino de degradación de RC-03."""
        return self.origen is not Origen.PROVEEDOR
