#!/usr/bin/env python3
"""Valida que un pull request siga GitFlow: nombre de la rama origen y destino permitido.

Ramas de larga vida: main (producción, solo releases y hotfixes) y develop (integración).
Ramas de vida corta:
  feature/HISTORIA-descripcion  → develop       (también fix/, docs/, ci/, test/, refactor/, chore/)
  release/vMAYOR.MENOR.0        → main          (sale de develop al cierre del sprint)
  hotfix/vMAYOR.MENOR.PARCHE    → main          (sale de main)
  fix/HISTORIA-descripcion      → release/v...  (correcciones durante la estabilización)
  main                          → develop       (back-merge tras cada release o hotfix)

Uso: validar_rama.py --origen <rama> --destino <rama>
"""

import argparse
import re
import sys

HISTORIA = r"(?:(?:ARQ|WEB|MOB)-F?\d{2}(?:-\d{2})?|\d+)"
TRABAJO = re.compile(
    rf"^(?:feature|fix|docs|ci|test|refactor|chore)/{HISTORIA}-[a-z0-9]+(?:-[a-z0-9]+)*$"
)
CORRECCION = re.compile(rf"^(?:fix|docs)/{HISTORIA}-[a-z0-9]+(?:-[a-z0-9]+)*$")
RELEASE = re.compile(r"^release/v\d+\.\d+\.0$")
HOTFIX = re.compile(r"^hotfix/v\d+\.\d+\.[1-9]\d*$")
DEPENDABOT = re.compile(r"^dependabot/")


def _permitida(origen: str, destino: str) -> bool:
    if destino == "main":
        return bool(RELEASE.match(origen) or HOTFIX.match(origen))
    if destino == "develop":
        return any(
            (
                TRABAJO.match(origen),
                RELEASE.match(origen),
                HOTFIX.match(origen),
                DEPENDABOT.match(origen),
                origen == "main",
            )
        )
    if RELEASE.match(destino):
        return bool(CORRECCION.match(origen))
    return False


def errores(origen: str, destino: str) -> list[str]:
    if _permitida(origen, destino):
        return []
    return [f'GitFlow no permite un pull request de "{origen}" hacia "{destino}"']


AYUDA = """
Flujos permitidos (GitFlow):
  feature|fix|docs|ci|test|refactor|chore/HISTORIA-descripcion  → develop
  release/vX.Y.0                                                → main (y back-merge a develop)
  hotfix/vX.Y.Z  (Z ≥ 1)                                        → main (y back-merge a develop)
  fix|docs/HISTORIA-descripcion                                 → release/vX.Y.0
  main                                                          → develop (back-merge)

HISTORIA es un ID del tablero (ARQ-04, WEB-F02, MOB-F01) o un número de issue (12).
Ejemplo: feature/WEB-F02-consentimiento-web → develop
"""


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--origen", required=True)
    parser.add_argument("--destino", required=True)
    args = parser.parse_args(argv)

    encontrados = errores(args.origen, args.destino)
    for error in encontrados:
        print(f"✗ {error}", file=sys.stderr)
    if encontrados:
        print(AYUDA, file=sys.stderr)
        return 1
    print(f"✓ {args.origen} → {args.destino} cumple GitFlow")
    return 0


if __name__ == "__main__":
    sys.exit(main())
