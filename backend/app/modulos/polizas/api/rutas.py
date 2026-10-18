"""Rutas HTTP del módulo de polizas, bajo la fachada /v1.

Todavía no publica ninguna: el módulo aparece en `/v1/` como esqueleto hasta que su
historia (WEB-F04) le dé la primera ruta.
"""

from __future__ import annotations

from fastapi import APIRouter

router = APIRouter(prefix="/polizas", tags=["polizas"])
