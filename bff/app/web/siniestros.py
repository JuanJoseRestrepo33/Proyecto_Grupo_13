"""Rutas de siniestros del canal web (WEB-F05), según `contratos/bff-web`.

Mismas rutas, formas y códigos en los dos canales: la orquestación está en
`app.comun.siniestros`. Aquí solo se ponen la sesión y el canal.
"""

from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Header, Response, status

from app.comun import siniestros
from app.comun.siniestros import SolicitudSiniestro

from .dependencias import CANAL, ClienteWeb, Nucleo

router = APIRouter(prefix="/siniestros", tags=["siniestros"])
router_polizas = APIRouter(prefix="/polizas", tags=["polizas"])

Clave = Annotated[str | None, Header(alias="Idempotency-Key")]


@router_polizas.get("", summary="Pólizas del cliente, para elegir la afectada")
def polizas(cliente: ClienteWeb, nucleo: Nucleo) -> list[dict[str, object]]:
    return siniestros.polizas(nucleo, cliente.id_cliente)


@router.get("", summary="Siniestros del cliente, del más reciente al más antiguo")
def listar(cliente: ClienteWeb, nucleo: Nucleo) -> list[dict[str, object]]:
    return siniestros.listar(nucleo, cliente.id_cliente)


@router.post("", status_code=status.HTTP_201_CREATED, summary="Reporta un siniestro")
def reportar(
    solicitud: SolicitudSiniestro,
    response: Response,
    cliente: ClienteWeb,
    nucleo: Nucleo,
    clave: Clave = None,
) -> dict[str, object]:
    """La misma `Idempotency-Key` devuelve el mismo siniestro con 200: un doble clic o un
    reintento no crea otro (criterio 4)."""
    siniestro, estado = siniestros.reportar(
        nucleo, cliente.id_cliente, CANAL, solicitud, siniestros.exigir_clave(clave)
    )
    response.status_code = estado
    return siniestro


@router.get("/{id_siniestro}", summary="Detalle de un siniestro")
def obtener(id_siniestro: str, cliente: ClienteWeb, nucleo: Nucleo) -> dict[str, object]:
    return siniestros.obtener(nucleo, cliente.id_cliente, id_siniestro)
