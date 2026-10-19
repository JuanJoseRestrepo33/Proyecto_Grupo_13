"""Descubrimiento de los módulos del monolito.

Cada paquete directo de `app.modulos` es una capacidad de negocio y debe exponer un
`MODULO: DescriptorDeModulo`. Un paquete que no lo haga rompe el arranque con un mensaje
que dice exactamente qué falta, en vez de quedar silenciosamente fuera de la fachada.
"""

from __future__ import annotations

import importlib
import pkgutil
from types import ModuleType

from app import modulos as paquete_por_defecto
from app.compartido.modulo import DescriptorDeModulo


class ModuloMalFormado(RuntimeError):
    """Un paquete de `app.modulos` no cumple el contrato de módulo."""


def descubrir_modulos(
    paquete: ModuleType = paquete_por_defecto,
) -> tuple[DescriptorDeModulo, ...]:
    """Importa cada subpaquete de `paquete` y devuelve sus descriptores, en orden alfabético.

    Se ignoran los paquetes cuyo nombre empieza por `_`, reservados para utilidades que no
    son capacidades de negocio.
    """
    descriptores: list[DescriptorDeModulo] = []
    for info in sorted(pkgutil.iter_modules(paquete.__path__), key=lambda i: i.name):
        if not info.ispkg or info.name.startswith("_"):
            continue
        ruta = f"{paquete.__name__}.{info.name}"
        modulo = importlib.import_module(ruta)
        descriptor = getattr(modulo, "MODULO", None)
        if not isinstance(descriptor, DescriptorDeModulo):
            raise ModuloMalFormado(
                f"{ruta} no expone `MODULO: DescriptorDeModulo`. "
                "Créalo con scripts/nuevo_modulo.py o agrégalo a su __init__.py."
            )
        if descriptor.nombre != info.name:
            raise ModuloMalFormado(
                f"{ruta} se describe como '{descriptor.nombre}' pero el paquete se llama "
                f"'{info.name}'. Deben coincidir."
            )
        descriptores.append(descriptor)
    return tuple(descriptores)
