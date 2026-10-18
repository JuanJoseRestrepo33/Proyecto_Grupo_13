"""Crea un módulo de negocio nuevo a partir de la plantilla del monolito (ARQ-01).

    python scripts/nuevo_modulo.py polizas --historia WEB-F04 \
        --descripcion "Consulta y ciclo de vida de pólizas"

Genera `app/modulos/<nombre>/` con las cuatro capas (dominio, aplicación, infraestructura,
api) y el descriptor `MODULO` que la fachada descubre sola. No toca ningún archivo
existente: al terminar, `/v1/` y `/salud` ya listan el módulo nuevo y `lint-imports` ya
le aplica los contratos de frontera.
"""

from __future__ import annotations

import argparse
import keyword
import re
from pathlib import Path

RAIZ_MODULOS = Path(__file__).resolve().parent.parent / "app" / "modulos"
NOMBRE_VALIDO = re.compile(r"^[a-z][a-z0-9_]*$")

PLANTILLA: dict[str, str] = {
    "__init__.py": '''"""{descripcion} ({historia})."""

from app.compartido.modulo import DescriptorDeModulo

from .api.rutas import router

MODULO = DescriptorDeModulo(
    nombre="{nombre}",
    descripcion="{descripcion}",
    historia="{historia}",
    router=router,
)
''',
    "dominio/__init__.py": (
        '"""Entidades y reglas de {nombre}. No conoce FastAPI ni la base de datos."""\n'
    ),
    "aplicacion/__init__.py": (
        '"""Casos de uso de {nombre}. Orquesta el dominio, no calcula."""\n'
    ),
    "infraestructura/__init__.py": (
        '"""Implementaciones de los puertos de {nombre}: persistencia y adaptadores."""\n'
    ),
    "api/__init__.py": "",
    "api/rutas.py": '''"""Rutas HTTP del módulo de {nombre}, bajo la fachada /v1.

Todavía no publica ninguna: el módulo aparece en `/v1/` como esqueleto hasta que su
historia ({historia}) le dé la primera ruta.
"""

from __future__ import annotations

from fastapi import APIRouter

router = APIRouter(prefix="/{nombre}", tags=["{nombre}"])
''',
}


class NombreDeModuloInvalido(ValueError):
    pass


class ModuloYaExiste(FileExistsError):
    pass


def validar_nombre(nombre: str) -> None:
    if not NOMBRE_VALIDO.match(nombre) or keyword.iskeyword(nombre):
        raise NombreDeModuloInvalido(
            f"'{nombre}' no sirve como nombre de módulo: usa minúsculas, dígitos y guion bajo, "
            "sin tildes, y que no sea una palabra reservada de Python."
        )


def crear_modulo(
    nombre: str,
    historia: str,
    descripcion: str,
    raiz: Path = RAIZ_MODULOS,
) -> Path:
    """Escribe la plantilla en `raiz/nombre` y devuelve esa ruta."""
    validar_nombre(nombre)
    destino = raiz / nombre
    if destino.exists():
        raise ModuloYaExiste(f"{destino} ya existe; elige otro nombre o bórralo primero.")
    valores = {"nombre": nombre, "historia": historia, "descripcion": descripcion}
    for relativo, contenido in PLANTILLA.items():
        archivo = destino / relativo
        archivo.parent.mkdir(parents=True, exist_ok=True)
        archivo.write_text(contenido.format(**valores), encoding="utf-8")
    return destino


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("nombre", help="nombre del paquete bajo app/modulos, en minúsculas")
    parser.add_argument("--historia", required=True, help="historia de origen, p. ej. WEB-F04")
    parser.add_argument("--descripcion", required=True, help="una línea sobre qué resuelve")
    args = parser.parse_args(argv)
    destino = crear_modulo(args.nombre, args.historia, args.descripcion)
    print(f"Módulo creado en {destino.relative_to(Path.cwd())}")
    print("Siguiente paso: lint-imports && pytest, y revisa /v1/ y /salud.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
