"""Rutas de consentimiento del canal móvil (MOB-F02), según `contratos/bff-movil`.

Mismas rutas, formas y códigos de error en los dos canales: la orquestación está en
`app.comun.consentimientos`. Aquí solo se ponen la sesión y el canal. La app nunca muestra
el `mensaje` de un error: traduce el `codigo`.
"""

from __future__ import annotations

from fastapi import APIRouter, status

from app.comun import consentimientos
from app.comun.consentimientos import SolicitudConsentimiento

from .dependencias import CANAL, ClienteMovil, Nucleo

router = APIRouter(prefix="/consentimientos", tags=["consentimientos"])


@router.get("/finalidades", summary="Finalidades que el cliente puede autorizar")
def finalidades(_: ClienteMovil, nucleo: Nucleo) -> list[dict[str, object]]:
    return consentimientos.finalidades(nucleo)


@router.get("", summary="Consentimientos del cliente, en cualquier estado")
def listar(cliente: ClienteMovil, nucleo: Nucleo) -> list[dict[str, object]]:
    return consentimientos.listar(nucleo, cliente.id_cliente)


@router.post("", status_code=status.HTTP_201_CREATED, summary="Otorga el consentimiento")
def otorgar(
    solicitud: SolicitudConsentimiento, cliente: ClienteMovil, nucleo: Nucleo
) -> dict[str, object]:
    """Ninguna entidad viene marcada: las elige el cliente. 409 `consentimiento_ya_vigente`;
    422 `entidades_invalidas` o `version_texto_desactualizada`."""
    return consentimientos.otorgar(nucleo, cliente.id_cliente, CANAL, solicitud)


@router.post("/{id_consentimiento}/revocacion", summary="Revoca un consentimiento vigente")
def revocar(id_consentimiento: str, cliente: ClienteMovil, nucleo: Nucleo) -> dict[str, object]:
    """Efectiva de inmediato en todos los canales: la siguiente cotización usa la tarifa
    estándar (RC-04)."""
    return consentimientos.revocar(nucleo, cliente.id_cliente, CANAL, id_consentimiento)


@router.post(
    "/{id_consentimiento}/renovacion",
    summary="Renueva un consentimiento sin repetir el flujo completo",
)
def renovar(id_consentimiento: str, cliente: ClienteMovil, nucleo: Nucleo) -> dict[str, object]:
    return consentimientos.renovar(nucleo, cliente.id_cliente, CANAL, id_consentimiento)


@router.get("/{id_consentimiento}/eventos", summary="Histórico en orden cronológico")
def eventos(
    id_consentimiento: str, cliente: ClienteMovil, nucleo: Nucleo
) -> list[dict[str, object]]:
    return consentimientos.eventos(nucleo, cliente.id_cliente, id_consentimiento)
