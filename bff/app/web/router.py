"""Router del canal web. Agrega las rutas de cada recurso del contrato `bff-web`."""

from __future__ import annotations

from fastapi import APIRouter

from . import consentimientos, cotizaciones, sesion, siniestros
from .dependencias import PREFIJO

router = APIRouter(prefix=PREFIJO)
router.include_router(sesion.router)
router.include_router(cotizaciones.router)
router.include_router(consentimientos.router)
router.include_router(siniestros.router)
router.include_router(siniestros.router_polizas)
