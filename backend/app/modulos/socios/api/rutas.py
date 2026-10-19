"""Rutas HTTP del módulo de socios, bajo la fachada /v1.

Todavía no publica ninguna: el módulo aparece en `/v1/` como esqueleto hasta que su
historia (API-F01) le dé la primera ruta.
"""

from __future__ import annotations

from fastapi import APIRouter

router = APIRouter(prefix="/socios", tags=["socios"])
