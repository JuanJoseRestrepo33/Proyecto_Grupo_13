"""Rutas HTTP del módulo de auditoría, bajo la fachada /v1.

Son de lectura. Los módulos escriben en el log en proceso, a través de la interfaz
pública del módulo, nunca por HTTP: el log no es una API que un canal pueda invocar.
"""

from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends

from ..aplicacion.servicio import ServicioDeAuditoria
from .dependencias import obtener_servicio
from .esquemas import InformeDeIntegridadDto, RegistroDto, a_informe_dto, a_registro_dto

router = APIRouter(prefix="/auditoria", tags=["auditoría"])

Servicio = Annotated[ServicioDeAuditoria, Depends(obtener_servicio)]


@router.get(
    "/registros/{id_decision}",
    response_model=RegistroDto,
    summary="Reconstruye una decisión tal como quedó registrada",
)
def reconstruir(id_decision: str, servicio: Servicio) -> RegistroDto:
    """Para actuaría o el regulador (RC-08): con `version_regla` y `variables_entrada`
    se recalcula la decisión y da el mismo resultado."""
    return a_registro_dto(servicio.reconstruir(id_decision))


@router.get(
    "/integridad",
    response_model=InformeDeIntegridadDto,
    summary="Verifica la cadena de hash completa",
)
def integridad(servicio: Servicio) -> InformeDeIntegridadDto:
    """Recorre todo el log y señala cada eslabón que no cuadra. Cero rotos es conforme."""
    return a_informe_dto(servicio.verificar_integridad())
