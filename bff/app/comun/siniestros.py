"""Reporte de siniestros contra el núcleo, igual para los dos canales (WEB-F05).

El reporte es el mismo en la web y en la app: mismo servicio, misma referencia, misma clave
de idempotencia. Por eso la orquestación vive aquí una vez y cada router solo pone su sesión
y su canal. El siniestro reportado desde un canal se ve en el otro.
"""

from __future__ import annotations

import uuid
from datetime import date
from typing import Any, Literal

from fastapi import status
from pydantic import BaseModel, Field

from .errores import ErrorBff
from .nucleo import ClienteNucleo, segmento

RUTA_NUCLEO = "/v1/siniestros"
RUTA_POLIZAS = "/v1/polizas"
NO_ENCONTRADO = "siniestro_no_encontrado"
MONEDA = "COP"

TipoEvento = Literal[
    "accidente_vehiculo",
    "hurto",
    "dano_hogar",
    "retraso_vuelo",
    "equipo_danado_o_perdido",
    "cancelacion_viaje",
    "otro",
]


class Dinero(BaseModel):
    valor: int = Field(ge=0)
    moneda: Literal["COP"] = MONEDA


class SolicitudSiniestro(BaseModel):
    numero_poliza: str = Field(min_length=1, max_length=64)
    tipo_evento: TipoEvento
    ocurrido_el: date
    descripcion: str = Field(min_length=10, max_length=2000)
    monto_estimado: Dinero | None = None


def exigir_clave(clave: str | None) -> str:
    """La clave de idempotencia la genera el cliente por cada envío del asistente (UUID)."""
    try:
        return str(uuid.UUID(clave or ""))
    except ValueError:
        raise ErrorBff(
            422,
            "clave_idempotencia_invalida",
            "Falta la clave de idempotencia del reporte o no es un UUID",
            extra={"campos": [{"campo": "Idempotency-Key", "codigo": "formato_invalido"}]},
        ) from None


def a_poliza(poliza: dict[str, Any]) -> dict[str, object]:
    return {
        "numero": poliza["numero"],
        "producto": poliza["producto"],
        "inicio": poliza["inicio"],
        "fin": poliza["fin"],
        "estado": poliza["estado"],
    }


def a_siniestro(siniestro: dict[str, Any]) -> dict[str, object]:
    monto = siniestro["monto_estimado"]
    return {
        "id": siniestro["id"],
        "referencia": siniestro["referencia"],
        "numero_poliza": siniestro["numero_poliza"],
        "tipo_evento": siniestro["tipo_evento"],
        "ocurrido_el": siniestro["ocurrido_el"],
        "descripcion": siniestro["descripcion"],
        "monto_estimado": None if monto is None else {"valor": monto, "moneda": MONEDA},
        "estado": siniestro["estado"],
        "siguiente_paso": siniestro["siguiente_paso"],
        "canal": siniestro["canal"],
        "reportado_en": siniestro["reportado_en"],
    }


def _ruta_cliente(id_cliente: str) -> str:
    return f"{RUTA_NUCLEO}/clientes/{id_cliente}/siniestros"


def ruta_siniestro(id_cliente: str, id_siniestro: str) -> str:
    """La ruta de un siniestro en el núcleo; el identificador llega del canal y se valida."""
    return f"{_ruta_cliente(id_cliente)}/{segmento(id_siniestro, NO_ENCONTRADO)}"


def polizas(nucleo: ClienteNucleo, id_cliente: str) -> list[dict[str, object]]:
    """Las pólizas del cliente, para elegir la afectada en el asistente."""
    return [a_poliza(p) for p in nucleo.get(f"{RUTA_POLIZAS}/clientes/{id_cliente}/polizas")]


def listar(nucleo: ClienteNucleo, id_cliente: str) -> list[dict[str, object]]:
    return [a_siniestro(s) for s in nucleo.get(_ruta_cliente(id_cliente))]


def obtener(nucleo: ClienteNucleo, id_cliente: str, id_siniestro: str) -> dict[str, object]:
    siniestro = nucleo.get(ruta_siniestro(id_cliente, id_siniestro), si_no_existe=NO_ENCONTRADO)
    return a_siniestro(siniestro)


def reportar(
    nucleo: ClienteNucleo,
    id_cliente: str,
    canal: str,
    solicitud: SolicitudSiniestro,
    clave: str,
) -> tuple[dict[str, object], int]:
    """El siniestro y el estado HTTP: 201 si es nuevo, 200 si es el reintento de uno ya hecho.

    Una fecha futura llega del núcleo como 400 y sale como el 422 del contrato, con el campo
    que el asistente marca. Un evento fuera de vigencia trae las fechas de la póliza para que
    el canal explique por qué no procede (criterio 3).
    """
    cuerpo = {
        "numero_poliza": solicitud.numero_poliza,
        "tipo_evento": solicitud.tipo_evento,
        "ocurrido_el": solicitud.ocurrido_el.isoformat(),
        "descripcion": solicitud.descripcion,
        "monto_estimado": solicitud.monto_estimado.valor if solicitud.monto_estimado else None,
    }
    try:
        respuesta = nucleo.solicitar(
            "POST",
            _ruta_cliente(id_cliente),
            canal=canal,
            json=cuerpo,
            encabezados={"Idempotency-Key": clave},
        )
    except ErrorBff as error:
        if error.codigo == "fecha_futura":
            error.estado = 422
            error.extra["campos"] = [{"campo": "ocurrido_el", "codigo": "fecha_futura"}]
        elif error.codigo == "evento_fuera_de_vigencia":
            error.extra["vigente_desde"] = error.detalles.get("vigente_desde")
            error.extra["vigente_hasta"] = error.detalles.get("vigente_hasta")
        raise
    nuevo = respuesta.status_code == status.HTTP_201_CREATED
    return a_siniestro(respuesta.json()), status.HTTP_201_CREATED if nuevo else status.HTTP_200_OK
