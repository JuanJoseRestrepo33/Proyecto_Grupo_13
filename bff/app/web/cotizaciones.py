"""Rutas de cotización del canal web (WEB-F01), según `contratos/bff-web`.

Traducen entre el contrato de la web y la API `/v1/cotizacion` del núcleo: montos como
`{valor, moneda}`, `tipo_tarifa` derivado de la base de perfilamiento y la descripción de
cada producto, que es texto del canal y no del núcleo. El cliente sale siempre de la sesión,
nunca del cuerpo, y una cotización de otro cliente responde 404 como si no existiera.
"""

from __future__ import annotations

from typing import Any, Literal

from fastapi import APIRouter, status
from pydantic import BaseModel, Field

from app.comun.errores import ErrorBff

from .dependencias import CANAL, ClienteWeb, Nucleo

router = APIRouter(prefix="/cotizaciones", tags=["cotizaciones"])

RUTA_NUCLEO = "/v1/cotizacion"
NO_ENCONTRADA = "cotizacion_no_encontrada"

#: Lo que la web muestra debajo del nombre de cada producto.
DESCRIPCIONES = {
    "auto": "Responsabilidad civil, todo riesgo y hurto.",
    "hogar": "Incendio, terremoto y contenidos.",
    "viaje": "Asistencia médica, equipaje y retraso de vuelo.",
    "dispositivos": "Daño accidental, robo y pérdida.",
}

CodigoProducto = Literal["auto", "hogar", "viaje", "dispositivos"]


class DatosAsegurado(BaseModel):
    edad: int = Field(ge=18, le=100)
    ciudad: str = Field(min_length=1, max_length=80)
    valor_asegurado: int | None = Field(default=None, ge=0)


class SolicitudCotizacion(BaseModel):
    producto: CodigoProducto
    asegurado: DatosAsegurado
    usar_open_finance: bool


def _dinero(valor: int, moneda: str) -> dict[str, object]:
    return {"valor": valor, "moneda": moneda}


def a_producto(producto: dict[str, Any]) -> dict[str, object]:
    return {
        "codigo": producto["id"],
        "nombre": producto["nombre"],
        "nombre_corto": producto["nombre_corto"],
        "descripcion": DESCRIPCIONES.get(producto["id"], ""),
        "prima_estandar": _dinero(producto["prima_estandar"], producto["moneda"]),
        "valor_asegurado": producto["valor_asegurado"],
        "deducible": producto["deducible"],
        "coberturas": producto["coberturas"],
    }


def a_cotizacion(cotizacion: dict[str, Any]) -> dict[str, object]:
    oferta = cotizacion["oferta"]
    moneda = oferta["moneda"]
    personalizada = oferta["uso_open_finance"]
    return {
        "id": cotizacion["id_cotizacion"],
        "producto": cotizacion["producto"],
        "nombre_producto": cotizacion["nombre_producto"],
        "estado": cotizacion["estado"],
        "prima_mensual": _dinero(oferta["prima_mensual"], moneda),
        "prima_estandar": _dinero(oferta["prima_estandar"], moneda),
        "ahorro_mensual": _dinero(oferta["ahorro_mensual"], moneda),
        "tipo_tarifa": "personalizada" if personalizada else "estandar",
        "base_perfilamiento": oferta["base_perfilamiento"],
        "motivo_tarifa": None if personalizada else cotizacion.get("motivo_tarifa"),
        "factores": [{"nombre": f["nombre"], "efecto": f["efecto"]} for f in oferta["factores"]],
        "version_regla": oferta["version_regla"],
        "valor_asegurado": oferta["valor_asegurado"],
        "deducible": oferta["deducible"],
        "coberturas": oferta["coberturas"],
        "creada_en": cotizacion["creada_en"],
        "vigente_hasta": cotizacion["expira_en"],
    }


def _propia(nucleo: Nucleo, id_cotizacion: str, cliente: ClienteWeb) -> dict[str, Any]:
    """La cotización si es del cliente de la sesión; si no, el mismo 404 que si no existiera."""
    cotizacion = nucleo.get(
        f"{RUTA_NUCLEO}/cotizaciones/{id_cotizacion}", si_no_existe=NO_ENCONTRADA
    )
    if cotizacion.get("id_cliente") != cliente.id_cliente:
        raise ErrorBff(status.HTTP_404_NOT_FOUND, NO_ENCONTRADA, "La cotización no existe")
    return cotizacion


@router.get("/productos", summary="Productos que se pueden cotizar")
def productos(_: ClienteWeb, nucleo: Nucleo) -> list[dict[str, object]]:
    return [a_producto(p) for p in nucleo.get(f"{RUTA_NUCLEO}/productos")]


@router.post("", status_code=status.HTTP_201_CREATED, summary="Cotiza un producto")
def cotizar(
    solicitud: SolicitudCotizacion, cliente: ClienteWeb, nucleo: Nucleo
) -> dict[str, object]:
    """Repetir la misma solicitud con la oferta vigente devuelve la misma cotización
    (idempotencia del núcleo): un doble clic no deja dos ofertas."""
    cotizacion = nucleo.post(
        f"{RUTA_NUCLEO}/cotizaciones",
        canal=CANAL,
        json={
            "producto": solicitud.producto,
            "asegurado": solicitud.asegurado.model_dump(),
            "id_cliente": cliente.id_cliente,
            "usar_open_finance": solicitud.usar_open_finance,
        },
    )
    return a_cotizacion(cotizacion)


@router.get("/{id_cotizacion}", summary="Consulta una cotización emitida")
def obtener(id_cotizacion: str, cliente: ClienteWeb, nucleo: Nucleo) -> dict[str, object]:
    return a_cotizacion(_propia(nucleo, id_cotizacion, cliente))


@router.post(
    "/{id_cotizacion}/recotizacion",
    status_code=status.HTTP_201_CREATED,
    summary="Recotiza con los mismos datos de una cotización vencida",
)
def recotizar(id_cotizacion: str, cliente: ClienteWeb, nucleo: Nucleo) -> dict[str, object]:
    _propia(nucleo, id_cotizacion, cliente)
    cotizacion = nucleo.post(
        f"{RUTA_NUCLEO}/cotizaciones/{id_cotizacion}/recotizar",
        canal=CANAL,
        si_no_existe=NO_ENCONTRADA,
    )
    return a_cotizacion(cotizacion)
