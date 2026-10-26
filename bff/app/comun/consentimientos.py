"""Consentimiento de Open Finance contra el núcleo, igual para los dos canales (MOB-F02).

El consentimiento es del cliente, no del canal: los dos contratos tienen las mismas rutas,
las mismas formas y los mismos códigos de error, así que la orquestación vive aquí una sola
vez. Cada router pone su sesión y su canal; el núcleo deja el canal en el histórico.
"""

from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, Field

from .errores import ErrorBff
from .nucleo import ClienteNucleo

RUTA_NUCLEO = "/v1/consentimiento"
NO_ENCONTRADO = "consentimiento_no_encontrado"

#: Errores de datos que el núcleo responde como 400 y los contratos como 422, con el campo
#: que el formulario tiene que marcar.
_CAMPO_POR_CODIGO = {
    "entidades_invalidas": ("entidades_origen", "no_admitido"),
    "version_texto_desactualizada": ("version_texto", "desactualizada"),
}


class SolicitudConsentimiento(BaseModel):
    finalidad: Literal["cotizacion", "suscripcion", "prevencion_fraude"]
    entidades_origen: list[str] = Field(min_length=1)
    version_texto: str = Field(min_length=1)


def a_finalidad(finalidad: dict[str, Any]) -> dict[str, object]:
    return {
        "codigo": finalidad["codigo"],
        "nombre": finalidad["nombre"],
        "descripcion": finalidad["descripcion"],
        "alcance": finalidad["alcance"],
        "vigencia_meses": finalidad["vigencia_meses"],
        "entidades_origen": finalidad["entidades_origen"],
        "version_texto": finalidad["version_texto"],
    }


def a_consentimiento(consentimiento: dict[str, Any]) -> dict[str, object]:
    return {
        "id": consentimiento["id"],
        "finalidad": consentimiento["finalidad"],
        "entidades_origen": consentimiento["entidades_origen"],
        "estado": consentimiento["estado"],
        "canal": consentimiento["canal"],
        "otorgado_en": consentimiento["otorgado_en"],
        "vence_en": consentimiento["vence_en"],
        "revocado_en": consentimiento["revocado_en"],
        "version_texto": consentimiento["version_texto"],
        "por_vencer": consentimiento["por_vencer"],
        "dias_para_vencer": consentimiento["dias_para_vencer"],
    }


def a_evento(evento: dict[str, Any]) -> dict[str, object]:
    return {
        "tipo": evento["tipo"],
        "ocurrido_en": evento["ocurrido_en"],
        "canal": evento["canal"],
        "version_texto": evento["version_texto"],
    }


def _ruta_cliente(id_cliente: str) -> str:
    return f"{RUTA_NUCLEO}/clientes/{id_cliente}/consentimientos"


def finalidades(nucleo: ClienteNucleo) -> list[dict[str, object]]:
    return [a_finalidad(f) for f in nucleo.get(f"{RUTA_NUCLEO}/finalidades")]


def listar(nucleo: ClienteNucleo, id_cliente: str) -> list[dict[str, object]]:
    return [a_consentimiento(c) for c in nucleo.get(_ruta_cliente(id_cliente))]


def otorgar(
    nucleo: ClienteNucleo, id_cliente: str, canal: str, solicitud: SolicitudConsentimiento
) -> dict[str, object]:
    try:
        consentimiento = nucleo.post(
            _ruta_cliente(id_cliente), canal=canal, json=solicitud.model_dump()
        )
    except ErrorBff as error:
        if error.codigo in _CAMPO_POR_CODIGO:
            campo, codigo = _CAMPO_POR_CODIGO[error.codigo]
            error.estado = 422
            error.extra["campos"] = [{"campo": campo, "codigo": codigo}]
        raise
    return a_consentimiento(consentimiento)


def revocar(
    nucleo: ClienteNucleo, id_cliente: str, canal: str, id_consentimiento: str
) -> dict[str, object]:
    consentimiento = nucleo.post(
        f"{_ruta_cliente(id_cliente)}/{id_consentimiento}/revocacion",
        canal=canal,
        si_no_existe=NO_ENCONTRADO,
    )
    return a_consentimiento(consentimiento)


def renovar(
    nucleo: ClienteNucleo, id_cliente: str, canal: str, id_consentimiento: str
) -> dict[str, object]:
    consentimiento = nucleo.post(
        f"{_ruta_cliente(id_cliente)}/{id_consentimiento}/renovacion",
        canal=canal,
        si_no_existe=NO_ENCONTRADO,
    )
    return a_consentimiento(consentimiento)


def eventos(
    nucleo: ClienteNucleo, id_cliente: str, id_consentimiento: str
) -> list[dict[str, object]]:
    historico = nucleo.get(
        f"{_ruta_cliente(id_cliente)}/{id_consentimiento}/eventos", si_no_existe=NO_ENCONTRADO
    )
    return [a_evento(e) for e in historico]
