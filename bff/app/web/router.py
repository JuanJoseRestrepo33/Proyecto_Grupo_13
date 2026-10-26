"""Router del canal web. Agrega las rutas de cada recurso del contrato `bff-web`."""

from __future__ import annotations

from fastapi import APIRouter

PREFIJO = "/api/v1/web"

router = APIRouter(prefix=PREFIJO)
