"""Checkout del canal web (WEB-F03), según `contratos/bff-web`.

Traduce entre el contrato de la web y la API `/v1/suscripcion` del núcleo: montos como
`{valor, moneda}`, `cotizacion_id` en vez de `id_cotizacion` y el último pago anidado. El
cliente sale siempre de la sesión, nunca del cuerpo, y una suscripción de otro cliente
responde 404 como si no existiera.

Comprar desde la app es MOB-F04, fuera del MVP: estas rutas solo existen en el router web.
"""

from __future__ import annotations

import uuid
from typing import Annotated, Any, Literal

from fastapi import APIRouter, Header, Response, status
from pydantic import BaseModel, Field

from app.comun.errores import ErrorBff
from app.comun.nucleo import segmento

from .dependencias import CANAL, ClienteWeb, Nucleo

router = APIRouter(prefix="/suscripciones", tags=["suscripciones"])

NO_ENCONTRADA = "suscripcion_no_encontrada"
PAGO_NO_ENCONTRADO = "pago_no_encontrado"

Clave = Annotated[str | None, Header(alias="Idempotency-Key")]
MedioPago = Literal["tarjeta_credito", "pse"]


class SolicitudSuscripcion(BaseModel):
    cotizacion_id: str = Field(min_length=1, max_length=128)


class SolicitudPago(BaseModel):
    medio: MedioPago


class SolicitudFirma(BaseModel):
    acepta_condiciones: bool


def _ruta(id_cliente: str) -> str:
    return f"/v1/suscripcion/clientes/{id_cliente}/suscripciones"


def _dinero(valor: int, moneda: str) -> dict[str, object]:
    return {"valor": valor, "moneda": moneda}


def a_pago(pago: dict[str, Any]) -> dict[str, object]:
    return {
        "id": pago["id"],
        "estado": pago["estado"],
        "medio": pago["medio"],
        "monto": _dinero(pago["monto"], pago["moneda"]),
        "referencia": pago["referencia"],
        "creado_en": pago["creado_en"],
        "reintento": pago.get("reintento", False),
    }


def a_suscripcion(suscripcion: dict[str, Any]) -> dict[str, object]:
    poliza = suscripcion.get("poliza")
    pago = suscripcion.get("pago")
    return {
        "id": suscripcion["id"],
        "cotizacion_id": suscripcion["id_cotizacion"],
        "nombre_producto": suscripcion["nombre_producto"],
        "prima_mensual": _dinero(suscripcion["prima_mensual"], suscripcion["moneda"]),
        "estado": suscripcion["estado"],
        "aceptada_en": suscripcion["aceptada_en"],
        "pago": None if pago is None else a_pago(pago),
        "poliza": None
        if poliza is None
        else {
            "numero": poliza["numero"],
            "inicio": poliza["inicio"],
            "fin": poliza["fin"],
            "documento_url": f"/api/v1/web/polizas/{poliza['numero']}/documento",
        },
        "firmada_en": suscripcion.get("firmada_en"),
    }


def _exigir_clave(clave: str | None) -> str:
    """Cada intento de pago lleva su clave, un UUID que genera la web."""
    try:
        return str(uuid.UUID(clave or ""))
    except ValueError:
        raise ErrorBff(
            422,
            "clave_idempotencia_invalida",
            "Falta la clave del intento de pago o no es un UUID",
            extra={"campos": [{"campo": "Idempotency-Key", "codigo": "formato_invalido"}]},
        ) from None


@router.post("", status_code=status.HTTP_201_CREATED, summary="Acepta una cotización vigente")
def aceptar(
    solicitud: SolicitudSuscripcion, response: Response, cliente: ClienteWeb, nucleo: Nucleo
) -> dict[str, object]:
    """201 con la suscripción nueva; 200 si la cotización ya estaba aceptada (la misma)."""
    respuesta = nucleo.solicitar(
        "POST",
        _ruta(cliente.id_cliente),
        canal=CANAL,
        json={"id_cotizacion": segmento(solicitud.cotizacion_id, "cotizacion_no_encontrada")},
    )
    response.status_code = respuesta.status_code
    return a_suscripcion(respuesta.json())


@router.get("/{id_suscripcion}", summary="Consulta una suscripción y su último pago")
def obtener(id_suscripcion: str, cliente: ClienteWeb, nucleo: Nucleo) -> dict[str, object]:
    suscripcion = nucleo.get(
        f"{_ruta(cliente.id_cliente)}/{segmento(id_suscripcion, NO_ENCONTRADA)}",
        si_no_existe=NO_ENCONTRADA,
    )
    return a_suscripcion(suscripcion)


@router.post(
    "/{id_suscripcion}/pagos",
    status_code=status.HTTP_201_CREATED,
    summary="Paga la suscripción contra la pasarela simulada",
)
def pagar(
    id_suscripcion: str,
    solicitud: SolicitudPago,
    response: Response,
    cliente: ClienteWeb,
    nucleo: Nucleo,
    clave: Clave = None,
) -> dict[str, object]:
    """La misma `Idempotency-Key` nunca cobra dos veces: 200 con `reintento: true` si el
    intento ya terminó, 409 `pago_en_curso` si sigue en curso (P18)."""
    clave = _exigir_clave(clave)
    respuesta = nucleo.solicitar(
        "POST",
        f"{_ruta(cliente.id_cliente)}/{segmento(id_suscripcion, NO_ENCONTRADA)}/pagos",
        canal=CANAL,
        json={"medio": solicitud.medio},
        encabezados={"Idempotency-Key": clave},
        si_no_existe=NO_ENCONTRADA,
    )
    response.status_code = respuesta.status_code
    return a_pago(respuesta.json())


@router.get(
    "/{id_suscripcion}/pagos/{id_pago}",
    summary="Consulta el estado de un pago, por ejemplo uno pendiente",
)
def obtener_pago(
    id_suscripcion: str, id_pago: str, cliente: ClienteWeb, nucleo: Nucleo
) -> dict[str, object]:
    ruta = (
        f"{_ruta(cliente.id_cliente)}/{segmento(id_suscripcion, PAGO_NO_ENCONTRADO)}"
        f"/pagos/{segmento(id_pago, PAGO_NO_ENCONTRADO)}"
    )
    return a_pago(nucleo.get(ruta, si_no_existe=PAGO_NO_ENCONTRADO))


@router.post(
    "/{id_suscripcion}/firma",
    status_code=status.HTTP_201_CREATED,
    summary="Firma electrónicamente y emite la póliza",
)
def firmar(
    id_suscripcion: str,
    solicitud: SolicitudFirma,
    response: Response,
    cliente: ClienteWeb,
    nucleo: Nucleo,
) -> dict[str, object]:
    """201 con la suscripción `emitida` y su póliza; 200 si ya estaba emitida. 409
    `pago_no_aprobado` si no hay un pago aprobado; 422 `aceptacion_requerida`."""
    respuesta = nucleo.solicitar(
        "POST",
        f"{_ruta(cliente.id_cliente)}/{segmento(id_suscripcion, NO_ENCONTRADA)}/firma",
        canal=CANAL,
        json={"acepta_condiciones": solicitud.acepta_condiciones},
        si_no_existe=NO_ENCONTRADA,
    )
    response.status_code = respuesta.status_code
    return a_suscripcion(respuesta.json())
