"""Rutas HTTP del módulo de perfilamiento, bajo la fachada /v1.

El perfil no se expone por HTTP: es un dato financiero del cliente que solo consumen
cotización y suscripción en proceso. Lo que sí se publica son las métricas del adaptador
(ARQ-02, criterio 5), para operación.
"""

from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends

from ..aplicacion.servicio import ServicioDePerfilamiento
from .dependencias import obtener_servicio

router = APIRouter(prefix="/perfilamiento", tags=["perfilamiento"])

Servicio = Annotated[ServicioDePerfilamiento, Depends(obtener_servicio)]


@router.get("/metricas", summary="Latencia, tasa de error y estado del circuito de Open Finance")
def metricas(servicio: Servicio) -> dict[str, object]:
    return servicio.metricas()
