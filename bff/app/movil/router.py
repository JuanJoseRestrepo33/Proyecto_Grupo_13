"""Router del canal móvil. Agrega las rutas de cada recurso del contrato `bff-movil`."""

from __future__ import annotations

from fastapi import APIRouter

PREFIJO = "/api/v1/movil"

router = APIRouter(prefix=PREFIJO)
