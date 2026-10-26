"""Rutas HTTP del módulo de cotización, bajo la fachada /v1.

La API es la del módulo, no la del canal: web y móvil la consumen a través de su propio
BFF. Aquí no hay nada específico de una pantalla. El BFF pasa el canal en la cabecera
`X-Canal`; sin ella, la cotización queda como pedida directo a la API.
"""

from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends, Header, status

from app.compartido.reloj import Reloj
from app.modulos.cotizacion.aplicacion.servicio import (
    ServicioDeCotizacion,
    SolicitudDeCotizacion,
)
from app.modulos.cotizacion.dominio import catalogo
from app.modulos.cotizacion.dominio.modelo import CANAL_API, DatosAsegurado

from .dependencias import obtener_reloj, obtener_servicio
from .esquemas import (
    Canal,
    CoberturaDto,
    CotizacionDto,
    ExplicacionDto,
    ProductoDto,
    RecalculoDto,
    SolicitudCotizacionDto,
    a_cotizacion_dto,
    a_explicacion_dto,
)

router = APIRouter(prefix="/cotizacion", tags=["cotización"])

# Dependencias del módulo, declaradas una vez y reutilizadas por todas las rutas.
Servicio = Annotated[ServicioDeCotizacion, Depends(obtener_servicio)]
RelojDep = Annotated[Reloj, Depends(obtener_reloj)]
CanalDep = Annotated[
    Canal | None, Header(alias="X-Canal", description="Canal que origina la solicitud")
]


@router.get(
    "/productos",
    response_model=list[ProductoDto],
    summary="Catálogo de productos cotizables",
)
def listar_productos(solo_web: bool = True) -> list[ProductoDto]:
    return [
        ProductoDto(
            id=p.id,
            nombre=p.nombre,
            nombre_corto=p.nombre_corto,
            prima_estandar=p.prima_estandar.monto,
            moneda=p.prima_estandar.moneda,
            valor_asegurado=p.valor_asegurado,
            deducible=p.deducible,
            coberturas=tuple(CoberturaDto(nombre=c.nombre, limite=c.limite) for c in p.coberturas),
        )
        for p in catalogo.listar(solo_web=solo_web)
    ]


@router.post(
    "/cotizaciones",
    response_model=CotizacionDto,
    status_code=status.HTTP_201_CREATED,
    summary="Solicitar una cotización y obtener la oferta",
)
def crear_cotizacion(
    solicitud: SolicitudCotizacionDto,
    servicio: Servicio,
    reloj: RelojDep,
    canal: CanalDep = None,
) -> CotizacionDto:
    cotizacion = servicio.cotizar(
        SolicitudDeCotizacion(
            producto=solicitud.producto,
            asegurado=DatosAsegurado(
                edad=solicitud.asegurado.edad,
                ciudad=solicitud.asegurado.ciudad,
                valor_asegurado=solicitud.asegurado.valor_asegurado,
            ),
            id_cliente=solicitud.id_cliente,
            usar_open_finance=solicitud.usar_open_finance,
            canal=str(canal) if canal else CANAL_API,
        )
    )
    return a_cotizacion_dto(cotizacion, reloj.ahora())


@router.get(
    "/cotizaciones/{id_cotizacion}",
    response_model=CotizacionDto,
    summary="Consultar una cotización",
)
def obtener_cotizacion(
    id_cotizacion: str,
    servicio: Servicio,
    reloj: RelojDep,
) -> CotizacionDto:
    """Devuelve la cotización aunque haya vencido.

    No falla al vencer, a propósito: el canal necesita leer el estado para ofrecer
    recotizar. Quien no puede tomar una oferta vencida es suscripción (WEB-F03), y esa
    regla la hace cumplir `Cotizacion.exigir_vigente`.
    """
    return a_cotizacion_dto(servicio.obtener(id_cotizacion), reloj.ahora())


@router.post(
    "/cotizaciones/{id_cotizacion}/recotizar",
    response_model=CotizacionDto,
    status_code=status.HTTP_201_CREATED,
    summary="Recotizar tras el vencimiento de la oferta",
)
def recotizar(
    id_cotizacion: str,
    servicio: Servicio,
    reloj: RelojDep,
) -> CotizacionDto:
    """Crea una cotización nueva a partir de los datos de otra. La anterior no cambia."""
    return a_cotizacion_dto(servicio.recotizar(id_cotizacion), reloj.ahora())


@router.get(
    "/cotizaciones/{id_cotizacion}/explicacion",
    response_model=ExplicacionDto,
    summary="¿Por qué este precio? Factores y versión de regla",
)
def explicar(
    id_cotizacion: str,
    servicio: Servicio,
) -> ExplicacionDto:
    return a_explicacion_dto(servicio.obtener(id_cotizacion))


@router.get(
    "/cotizaciones/{id_cotizacion}/recalculo",
    response_model=RecalculoDto,
    summary="Recalcula la prima con la decisión auditada",
)
def recalcular(id_cotizacion: str, servicio: Servicio) -> RecalculoDto:
    """Lee la versión de regla y las variables de entrada del log de auditoría y vuelve a
    calcular la prima. `coincide` en false significaría que la decisión registrada ya no
    explica el precio emitido. 404 si la decisión todavía no está en el log."""
    recalculo = servicio.recalcular(id_cotizacion)
    return RecalculoDto(
        id_cotizacion=recalculo.id_cotizacion,
        id_decision=recalculo.id_decision,
        version_regla=recalculo.version_regla,
        prima_emitida=recalculo.prima_emitida,
        prima_recalculada=recalculo.prima_recalculada,
        coincide=recalculo.coincide,
    )
