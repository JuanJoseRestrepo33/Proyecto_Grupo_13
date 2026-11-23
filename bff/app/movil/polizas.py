"""Pólizas del canal móvil (WEB-F04), según `contratos/bff-movil`.

Mismas rutas, formas y códigos en los dos canales: la traducción está en `app.comun.polizas`.
Aquí solo se pone la sesión y el prefijo del canal.
"""

from __future__ import annotations

from fastapi import APIRouter, Response

from app.comun import polizas

from .dependencias import PREFIJO, ClienteMovil, Nucleo

router = APIRouter(prefix="/polizas", tags=["polizas"])


@router.get("", summary="Pólizas del cliente, de la más reciente a la más antigua")
def listar(cliente: ClienteMovil, nucleo: Nucleo) -> list[dict[str, object]]:
    return polizas.listar(nucleo, cliente.id_cliente, PREFIJO)


@router.get("/{numero}", summary="Detalle de una póliza: coberturas, deducible y límites")
def detalle(numero: str, cliente: ClienteMovil, nucleo: Nucleo) -> dict[str, object]:
    return polizas.detalle(nucleo, cliente.id_cliente, numero, PREFIJO)


@router.get("/{numero}/documento", response_class=Response, summary="Documento PDF")
def documento(numero: str, cliente: ClienteMovil, nucleo: Nucleo) -> Response:
    contenido, disposicion = polizas.documento(nucleo, cliente.id_cliente, numero)
    return Response(
        content=contenido,
        media_type="application/pdf",
        headers={"Content-Disposition": disposicion, "X-Content-Type-Options": "nosniff"},
    )
