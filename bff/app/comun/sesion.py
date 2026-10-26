"""Sesión del cliente: de un token a un cliente autenticado.

Cada router saca el token de donde su contrato lo pone (cookie en la web, `Authorization:
Bearer` en la app) y lo valida aquí. El token está ligado a su canal: una cookie de la web
no abre sesión en la app ni al revés.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime

from fastapi import Request, status

from .errores import ErrorBff
from .tokens import TokenInvalido, VerificadorDeTokens

CODIGO_NO_VIGENTE = "sesion_no_vigente"


@dataclass(frozen=True)
class ClienteAutenticado:
    id_cliente: str
    expira_en: datetime


def sesion_no_vigente(encabezados: dict[str, str] | None = None) -> ErrorBff:
    """El 401 de toda ruta protegida: falta el token, venció o su firma no es válida."""
    return ErrorBff(
        status.HTTP_401_UNAUTHORIZED,
        CODIGO_NO_VIGENTE,
        "No hay una sesión vigente",
        encabezados=encabezados,
    )


def autenticar(
    request: Request,
    token: str | None,
    canal: str,
    encabezados_401: dict[str, str] | None = None,
) -> ClienteAutenticado:
    """Valida el token para el canal y devuelve el cliente, o lanza el 401 del contrato.

    Sin llave configurada no hay con qué validar: es un 503, no un 401, porque el problema
    es del servicio y no de la sesión del cliente. Sin token, en cambio, es 401 siempre.
    """
    if not token:
        raise sesion_no_vigente(encabezados_401)
    verificador: VerificadorDeTokens = request.app.state.verificador
    if not verificador.configurado:
        raise ErrorBff(
            status.HTTP_503_SERVICE_UNAVAILABLE,
            "llave_no_configurada",
            "El BFF no tiene la llave pública de los tokens",
        )
    try:
        reclamos = verificador.verificar(token)
    except TokenInvalido as error:
        raise sesion_no_vigente(encabezados_401) from error
    if reclamos.canal != canal:
        raise sesion_no_vigente(encabezados_401)
    return ClienteAutenticado(id_cliente=reclamos.id_cliente, expira_en=reclamos.expira_en)
