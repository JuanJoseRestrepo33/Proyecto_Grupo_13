"""Rutas HTTP del módulo de pólizas, bajo la fachada /v1.

    GET /polizas/clientes/{c}/polizas                      lista (WEB-F04 criterio 1; WEB-F05)
    GET /polizas/clientes/{c}/polizas/{numero}             detalle con coberturas (criterio 4)
    GET /polizas/clientes/{c}/polizas/{numero}/documento   PDF de una póliza emitida (WEB-F03)

El núcleo no autentica: el BFF resuelve la sesión y pasa el cliente en la ruta. Una póliza de
otro cliente responde 404, igual que si no existiera. Dentro de `v1` solo se agregan campos
opcionales: el reporte de siniestros sigue leyendo los mismos cinco de antes.
"""

from __future__ import annotations

from datetime import date, datetime
from typing import Annotated

from fastapi import APIRouter, Depends, Response
from pydantic import BaseModel, ConfigDict

from ..aplicacion.servicio import ServicioDePolizas
from ..dominio.modelo import EstadoPoliza, Poliza
from .dependencias import obtener_servicio

router = APIRouter(prefix="/polizas", tags=["polizas"])

Servicio = Annotated[ServicioDePolizas, Depends(obtener_servicio)]


class _Dto(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


class CoberturaDto(_Dto):
    nombre: str
    limite: str


class PolizaDto(_Dto):
    numero: str
    producto: str
    inicio: date
    fin: date
    estado: EstadoPoliza
    nombre_producto: str | None = None
    prima_mensual: int | None = None
    moneda: str | None = None
    #: Si se puede descargar su documento (solo las emitidas lo tienen).
    tiene_documento: bool = False
    #: Si viene de una compra (WEB-F03) o es una póliza de prueba de la semilla.
    emitida: bool = False


class PolizaDetalleDto(PolizaDto):
    coberturas: tuple[CoberturaDto, ...] = ()
    deducible: str | None = None
    valor_asegurado: str | None = None
    emitida_en: datetime | None = None


def _resumen(poliza: Poliza, servicio: ServicioDePolizas, hoy: date) -> dict[str, object]:
    prima = poliza.prima_mensual
    return {
        "numero": poliza.numero,
        "producto": poliza.producto,
        "inicio": poliza.inicio,
        "fin": poliza.fin,
        "estado": poliza.estado(hoy),
        "nombre_producto": poliza.nombre_producto,
        "prima_mensual": None if prima is None else prima.monto,
        "moneda": None if prima is None else prima.moneda,
        "tiene_documento": servicio.tiene_documento(poliza),
        "emitida": poliza.emitida,
    }


@router.get(
    "/clientes/{id_cliente}/polizas",
    response_model=tuple[PolizaDto, ...],
    summary="Pólizas del cliente, de la más reciente a la más antigua",
)
def del_cliente(id_cliente: str, servicio: Servicio) -> tuple[PolizaDto, ...]:
    """Vacía si el cliente no tiene pólizas: el canal muestra el estado vacío (criterio 3)."""
    hoy = servicio.hoy()
    return tuple(PolizaDto(**_resumen(p, servicio, hoy)) for p in servicio.del_cliente(id_cliente))


@router.get(
    "/clientes/{id_cliente}/polizas/{numero}",
    response_model=PolizaDetalleDto,
    summary="Detalle de una póliza: coberturas, deducible y límites",
)
def detalle(id_cliente: str, numero: str, servicio: Servicio) -> PolizaDetalleDto:
    """404 `poliza_no_encontrada` si no existe o es de otro cliente."""
    poliza = servicio.detalle(numero, id_cliente)
    return PolizaDetalleDto(
        **_resumen(poliza, servicio, servicio.hoy()),
        coberturas=tuple(CoberturaDto(nombre=c.nombre, limite=c.limite) for c in poliza.coberturas),
        deducible=poliza.deducible,
        valor_asegurado=poliza.valor_asegurado,
        emitida_en=poliza.emitida_en,
    )


@router.get(
    "/clientes/{id_cliente}/polizas/{numero}/documento",
    response_class=Response,
    responses={200: {"content": {"application/pdf": {}}}},
    summary="Documento PDF de una póliza emitida",
)
def documento(id_cliente: str, numero: str, servicio: Servicio) -> Response:
    """404 `poliza_no_encontrada`, o `documento_no_disponible` si la póliza no tiene documento
    (las pólizas de prueba de la semilla)."""
    poliza, contenido = servicio.documento(numero, id_cliente)
    return Response(
        content=contenido,
        media_type="application/pdf",
        headers={"Content-Disposition": f'attachment; filename="{poliza.numero}.pdf"'},
    )
