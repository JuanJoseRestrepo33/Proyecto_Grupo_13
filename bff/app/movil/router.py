"""Router del canal móvil. Agrega las rutas de cada recurso del contrato `bff-movil`."""

from __future__ import annotations

from fastapi import APIRouter

from . import sesiones
from .dependencias import PREFIJO

router = APIRouter(prefix=PREFIJO)
router.include_router(sesiones.router)
