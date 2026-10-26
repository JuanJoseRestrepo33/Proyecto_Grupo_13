"""Pólizas de prueba de *staging*, leídas de un JSON al arrancar (`POLIZAS_SEMILLA`).

Las fechas son relativas al día del arranque para que la semilla no caduque: una póliza
"vigente" lo sigue siendo en cada revisión nueva. Cargarla dos veces deja lo mismo.

    [
      {"numero": "POL-2026-000101", "id_cliente": "*", "producto": "auto",
       "desde_dias": -30, "hasta_dias": 335},
      {"numero": "POL-2026-000102", "id_cliente": "*", "producto": "hogar",
       "desde_dias": -395, "hasta_dias": -30}
    ]

`id_cliente` en `"*"` es una plantilla: la tiene cualquier cliente, con número propio. En
producción la variable no se define y no hay pólizas de prueba.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import date, timedelta
from pathlib import Path

from ..dominio.modelo import PlantillaDePoliza, Poliza

CUALQUIER_CLIENTE = "*"


class SemillaInvalidaError(ValueError):
    """El archivo de la semilla no tiene la forma esperada."""


@dataclass(frozen=True)
class Semilla:
    polizas: tuple[Poliza, ...] = ()
    plantillas: tuple[PlantillaDePoliza, ...] = ()


def interpretar(entradas: object, hoy: date) -> Semilla:
    if not isinstance(entradas, list):
        raise SemillaInvalidaError("la semilla debe ser una lista de pólizas")
    polizas, plantillas = [], []
    for entrada in entradas:
        try:
            inicio = hoy + timedelta(days=int(entrada["desde_dias"]))
            fin = hoy + timedelta(days=int(entrada["hasta_dias"]))
            numero, producto = str(entrada["numero"]), str(entrada["producto"])
            id_cliente = str(entrada["id_cliente"])
        except (KeyError, TypeError, ValueError) as error:
            raise SemillaInvalidaError(f"entrada inválida en la semilla: {entrada!r}") from error
        if id_cliente == CUALQUIER_CLIENTE:
            plantillas.append(PlantillaDePoliza(numero, producto, inicio, fin))
        else:
            polizas.append(Poliza(numero, id_cliente, producto, inicio, fin))
    return Semilla(tuple(polizas), tuple(plantillas))


def cargar(ruta: str | Path, hoy: date) -> Semilla:
    try:
        contenido = json.loads(Path(ruta).read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise SemillaInvalidaError(f"no se pudo leer la semilla {ruta}") from error
    return interpretar(contenido, hoy)
