"""Rutas HTTP del módulo de consentimiento, bajo la fachada /v1.

El núcleo no autentica: el BFF resuelve la sesión y pasa el cliente en la ruta y el canal
en la cabecera `X-Canal`. Por eso ninguna ruta acepta un consentimiento que no sea del
cliente indicado: responde 404, igual que si no existiera.
"""

from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends, Header, status

from app.compartido.reloj import Reloj

from ..aplicacion.servicio import ServicioDeConsentimiento
from .dependencias import obtener_reloj, obtener_servicio
from .esquemas import (
    Canal,
    ConsentimientoDto,
    EventoConsentimientoDto,
    FinalidadDto,
    SolicitudConsentimientoDto,
    a_consentimiento_dto,
    a_evento_dto,
    a_finalidad_dto,
)

router = APIRouter(prefix="/consentimiento", tags=["consentimiento"])

Servicio = Annotated[ServicioDeConsentimiento, Depends(obtener_servicio)]
RelojDep = Annotated[Reloj, Depends(obtener_reloj)]
CanalDep = Annotated[Canal, Header(alias="X-Canal", description="Canal que origina la acción")]

RUTA_CLIENTE = "/clientes/{id_cliente}/consentimientos"


@router.get(
    "/finalidades",
    response_model=tuple[FinalidadDto, ...],
    summary="Finalidades que el cliente puede autorizar, cada una por separado",
)
def finalidades(servicio: Servicio) -> tuple[FinalidadDto, ...]:
    """Cada una con su alcance, su vigencia, las entidades origen y la versión del texto
    legal que se acepta al otorgarla. Ninguna viene marcada: eso es decisión del cliente."""
    return tuple(a_finalidad_dto(f) for f in servicio.finalidades())


@router.get(
    RUTA_CLIENTE,
    response_model=tuple[ConsentimientoDto, ...],
    summary="Consentimientos del cliente, en cualquier estado",
)
def listar(id_cliente: str, servicio: Servicio, reloj: RelojDep) -> tuple[ConsentimientoDto, ...]:
    ahora = reloj.ahora()
    return tuple(a_consentimiento_dto(c, ahora) for c in servicio.listar(id_cliente))


@router.post(
    RUTA_CLIENTE,
    response_model=ConsentimientoDto,
    status_code=status.HTTP_201_CREATED,
    summary="Otorga el consentimiento para una finalidad",
)
def otorgar(
    id_cliente: str,
    solicitud: SolicitudConsentimientoDto,
    servicio: Servicio,
    reloj: RelojDep,
    canal: CanalDep = Canal.WEB,
) -> ConsentimientoDto:
    consentimiento = servicio.otorgar(
        id_cliente=id_cliente,
        finalidad=solicitud.finalidad,
        entidades_origen=solicitud.entidades_origen,
        version_texto=solicitud.version_texto,
        canal=str(canal),
    )
    return a_consentimiento_dto(consentimiento, reloj.ahora())


@router.get(
    RUTA_CLIENTE + "/{id_consentimiento}",
    response_model=ConsentimientoDto,
    summary="Detalle de un consentimiento",
)
def obtener(
    id_cliente: str, id_consentimiento: str, servicio: Servicio, reloj: RelojDep
) -> ConsentimientoDto:
    return a_consentimiento_dto(servicio.obtener(id_cliente, id_consentimiento), reloj.ahora())


@router.post(
    RUTA_CLIENTE + "/{id_consentimiento}/revocacion",
    response_model=ConsentimientoDto,
    summary="Revoca un consentimiento vigente",
)
def revocar(
    id_cliente: str,
    id_consentimiento: str,
    servicio: Servicio,
    reloj: RelojDep,
    canal: CanalDep = Canal.WEB,
) -> ConsentimientoDto:
    """Efectiva de inmediato: la siguiente cotización del cliente usa tarifa estándar y la
    caché de perfil queda vacía (criterios 3, 4 y 5)."""
    consentimiento = servicio.revocar(id_cliente, id_consentimiento, str(canal))
    return a_consentimiento_dto(consentimiento, reloj.ahora())


@router.post(
    RUTA_CLIENTE + "/{id_consentimiento}/renovacion",
    response_model=ConsentimientoDto,
    summary="Renueva un consentimiento vigente o vencido sin repetir el flujo completo",
)
def renovar(
    id_cliente: str,
    id_consentimiento: str,
    servicio: Servicio,
    reloj: RelojDep,
    canal: CanalDep = Canal.WEB,
) -> ConsentimientoDto:
    consentimiento = servicio.renovar(id_cliente, id_consentimiento, str(canal))
    return a_consentimiento_dto(consentimiento, reloj.ahora())


@router.get(
    RUTA_CLIENTE + "/{id_consentimiento}/eventos",
    response_model=tuple[EventoConsentimientoDto, ...],
    summary="Histórico del consentimiento en orden cronológico",
)
def eventos(
    id_cliente: str, id_consentimiento: str, servicio: Servicio
) -> tuple[EventoConsentimientoDto, ...]:
    """Del más antiguo al más reciente, incluidos revocados y vencidos (criterio 8)."""
    return tuple(a_evento_dto(e) for e in servicio.eventos(id_cliente, id_consentimiento))
