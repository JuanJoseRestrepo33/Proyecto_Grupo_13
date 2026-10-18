"""Rutas HTTP del módulo de identidad, bajo la fachada /v1.

Todavía no publica ninguna: el módulo aparece en `/v1/` como esqueleto hasta que su
historia (MOB-F01) le dé la primera ruta.
"""

from __future__ import annotations

from fastapi import APIRouter

router = APIRouter(prefix="/identidad", tags=["identidad"])
