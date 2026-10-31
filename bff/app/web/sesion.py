"""Sesión del canal web (WEB-F13), según `contratos/bff-web` 0.5.0.

Iniciar sesión pone la cookie `sesion` con el token de acceso que emite el núcleo; la web
nunca lo ve. La cookie dura lo mismo que el token (60 minutos para la web): al vencer, toda
ruta protegida responde 401 `sesion_no_vigente` y la web vuelve al inicio de sesión. Cerrar
sesión siempre responde 204, aunque la cookie ya no sirva.
"""

from __future__ import annotations

from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, Response, status
from pydantic import BaseModel, Field

from app.comun.sesion import cuenta_vigente

from .dependencias import CANAL, COOKIE_SESION, PREFIJO, ClienteWeb, Nucleo

router = APIRouter(prefix="/sesion", tags=["sesion"])

RUTA_NUCLEO = "/v1/identidad"
_CORREO = r"^[^@\s]+@[^@\s]+\.[^@\s]+$"


class Credenciales(BaseModel):
    correo: str = Field(max_length=254, pattern=_CORREO)
    contrasena: str = Field(min_length=1, max_length=128, repr=False)


def _nombre(nombre_completo: str) -> str:
    """La web saluda por el primer nombre (`Sesion.cliente.nombre`)."""
    return nombre_completo.split()[0] if nombre_completo.strip() else nombre_completo


def a_sesion(cliente: dict[str, Any]) -> dict[str, object]:
    return {"cliente": {"id": cliente["id"], "nombre": _nombre(cliente["nombre_completo"])}}


def _segundos_hasta(expira_en: str) -> int:
    vence = datetime.fromisoformat(expira_en)
    return max(int((vence - datetime.now(UTC)).total_seconds()), 0)


@router.get("", summary="Cliente autenticado")
def obtener(cliente: ClienteWeb, nucleo: Nucleo) -> dict[str, object]:
    """Si la cuenta ya no existe en el núcleo (por ejemplo, tras una revisión nueva con las
    cuentas en memoria), la sesión no es vigente: la web vuelve a iniciar sesión."""
    with cuenta_vigente():
        cuenta = nucleo.get(f"{RUTA_NUCLEO}/clientes/{cliente.id_cliente}")
    return a_sesion(cuenta)


@router.post("", summary="Inicia sesión con correo y contraseña")
def iniciar(credenciales: Credenciales, response: Response, nucleo: Nucleo) -> dict[str, object]:
    """401 `credenciales_invalidas`, igual exista o no el correo (criterio 3)."""
    sesion = nucleo.post(
        f"{RUTA_NUCLEO}/sesiones",
        canal=CANAL,
        json={"correo": credenciales.correo, "contrasena": credenciales.contrasena},
    )
    response.set_cookie(
        COOKIE_SESION,
        sesion["token_acceso"],
        max_age=_segundos_hasta(sesion["expira_en"]),
        path=PREFIJO,
        secure=True,
        httponly=True,
        samesite="lax",
    )
    return a_sesion(sesion["cliente"])


@router.delete("", status_code=status.HTTP_204_NO_CONTENT, summary="Cierra la sesión")
def cerrar(response: Response) -> None:
    """No exige sesión: con la cookie vencida o inválida también la borra (204)."""
    response.delete_cookie(COOKIE_SESION, path=PREFIJO, secure=True, httponly=True, samesite="lax")
