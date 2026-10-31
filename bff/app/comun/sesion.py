"""Sesión del cliente: de un token a un cliente autenticado.

Cada router saca el token de donde su contrato lo pone (cookie en la web, `Authorization:
Bearer` en la app) y lo valida aquí. El token está ligado a su canal: una cookie de la web
no abre sesión en la app ni al revés.
"""

from __future__ import annotations

from collections.abc import Iterator
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime

from fastapi import Request, status

from .errores import ErrorBff
from .tokens import TokenInvalido, VerificadorDeTokens

CODIGO_NO_VIGENTE = "sesion_no_vigente"
CODIGO_CLIENTE_NO_ENCONTRADO = "cliente_no_encontrado"


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


@contextmanager
def cuenta_vigente(encabezados_401: dict[str, str] | None = None) -> Iterator[None]:
    """Envuelve una llamada al núcleo hecha con el cliente de la sesión.

    Si la cuenta ya no existe (viven en memoria y un despliegue las borra), la sesión no es
    vigente. Solo ese 404: cualquier otro, como una ruta que falta en el núcleo, es un problema
    del servicio y no puede verse como una sesión vencida.
    """
    try:
        yield
    except ErrorBff as error:
        if error.codigo == CODIGO_CLIENTE_NO_ENCONTRADO:
            raise sesion_no_vigente(encabezados_401) from error
        raise


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
