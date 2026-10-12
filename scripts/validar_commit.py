#!/usr/bin/env python3
"""Valida mensajes de commit: Conventional Commits + referencia a una historia del tablero.

Formato:  tipo(alcance opcional): descripción en minúscula (HISTORIA)
Ejemplo:  feat(consentimiento): revocar consentimiento desde la web (WEB-F02)

La historia puede ir en la cabecera o en el cuerpo/pie (p. ej. "Refs: ARQ-04" o "Closes #12").

Uso:
  validar_commit.py <archivo>          # hook commit-msg (pre-commit pasa el archivo)
  validar_commit.py --mensaje "..."    # un mensaje suelto (p. ej. el título del PR)
  validar_commit.py --rango A..B       # todos los commits no-merge del rango (CI)
"""

import argparse
import re
import subprocess
import sys

TIPOS = (
    "feat",
    "fix",
    "docs",
    "style",
    "refactor",
    "perf",
    "test",
    "build",
    "ci",
    "chore",
    "revert",
)
LONGITUD_MAXIMA = 100

CABECERA = re.compile(rf"^(?:{'|'.join(TIPOS)})(?:\([a-z0-9._/-]+\))?!?: \S.*$")
HISTORIA = re.compile(r"\b(?:ARQ|WEB|MOB)-F?\d{2}(?:-\d{2})?\b|#\d+")
EXENTO = re.compile(r"^(?:Merge |Revert \"|fixup! |squash! |amend! )")
# Los PR de release y de back-merge agrupan varias historias: no exigen una en particular.
RELEASE = re.compile(r"^chore\(release\): v\d+\.\d+\.\d+\b")

AYUDA = f"""
Formato esperado:
  tipo(alcance): descripción (HISTORIA)

  tipo      uno de: {", ".join(TIPOS)}
  alcance   opcional, en minúscula: backend, web, mobile, ci, consentimiento...
  HISTORIA  ID del tablero (ARQ-04, WEB-F02, MOB-F01) o issue (#12),
            en la cabecera o en el cuerpo ("Refs: ARQ-04")

Ejemplo:
  feat(consentimiento): revocar consentimiento desde la web (WEB-F02)
"""


def errores(mensaje: str) -> list[str]:
    lineas = [linea for linea in mensaje.splitlines() if not linea.startswith("#")]
    while lineas and not lineas[0].strip():
        lineas.pop(0)
    if not lineas:
        return ["el mensaje está vacío"]

    cabecera = lineas[0].rstrip()
    if EXENTO.match(cabecera):
        return []

    encontrados = []
    if not CABECERA.match(cabecera):
        encontrados.append(f'la cabecera no sigue Conventional Commits: "{cabecera}"')
    if len(cabecera) > LONGITUD_MAXIMA:
        encontrados.append(f"la cabecera supera {LONGITUD_MAXIMA} caracteres ({len(cabecera)})")
    if not RELEASE.match(cabecera) and not HISTORIA.search("\n".join(lineas)):
        encontrados.append("no referencia ninguna historia del tablero (ARQ-04, WEB-F02, #12...)")
    return encontrados


def commits_del_rango(rango: str) -> list[tuple[str, str]]:
    salida = subprocess.run(
        ["git", "log", "--no-merges", "--format=%h%x1f%B%x1e", rango],
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    commits = []
    for bloque in salida.split("\x1e"):
        if bloque.strip():
            sha, mensaje = bloque.strip("\n").split("\x1f", 1)
            commits.append((sha, mensaje))
    return commits


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    grupo = parser.add_mutually_exclusive_group(required=True)
    grupo.add_argument("archivo", nargs="?")
    grupo.add_argument("--mensaje")
    grupo.add_argument("--rango")
    args = parser.parse_args(argv)

    if args.rango:
        mensajes = commits_del_rango(args.rango)
    elif args.mensaje is not None:
        mensajes = [("mensaje", args.mensaje)]
    else:
        with open(args.archivo, encoding="utf-8") as archivo:
            mensajes = [("commit", archivo.read())]

    fallidos = 0
    for origen, mensaje in mensajes:
        for error in errores(mensaje):
            print(f"✗ {origen}: {error}", file=sys.stderr)
            fallidos += 1
    if fallidos:
        print(AYUDA, file=sys.stderr)
        return 1
    print(f"✓ {len(mensajes)} mensaje(s) cumplen la convención")
    return 0


if __name__ == "__main__":
    sys.exit(main())
