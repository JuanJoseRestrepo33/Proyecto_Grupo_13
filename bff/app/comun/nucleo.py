"""Cliente HTTP del núcleo.

Es la única puerta del BFF hacia el núcleo. Traduce sus errores (`application/problem+json`,
RFC 9457) al formato de los contratos de canal y convierte una caída de red en un 503 con
código propio, para que ningún canal vea una excepción de `httpx`.
"""

from __future__ import annotations

import re
from collections.abc import Mapping
from typing import Any

import httpx
from fastapi import Request, status

from .errores import ErrorBff

#: 422 por número: Starlette renombró la constante entre versiones.
_SOLICITUD_INVALIDA = 422
#: Estados del núcleo que llegan al canal tal cual, con su código.
_ESTADOS_QUE_PASAN = frozenset({400, 401, 404, 409, 422, 429, 503})
#: Forma de todo identificador que llega del canal y entra en una ruta del núcleo.
_SEGMENTO = re.compile(r"[A-Za-z0-9_-]{1,128}")
#: Tiempo máximo de la consulta de `/salud` del núcleo desde la sonda del BFF.
_ESPERA_SALUD = 1.0


def _traducir(respuesta: httpx.Response) -> ErrorBff:
    try:
        cuerpo = respuesta.json()
    except ValueError:
        cuerpo = {}
    if not isinstance(cuerpo, dict):
        cuerpo = {}

    estado = respuesta.status_code
    if estado == _SOLICITUD_INVALIDA and "title" not in cuerpo:
        # Validación de FastAPI en el núcleo: el BFF validó antes, así que es un desfase
        # entre contratos. Se reporta sin el detalle interno.
        return ErrorBff(estado, "solicitud_invalida", "El núcleo rechazó los datos")
    if estado not in _ESTADOS_QUE_PASAN:
        return ErrorBff(
            status.HTTP_502_BAD_GATEWAY, "nucleo_con_error", f"El núcleo respondió {estado}"
        )

    encabezados = {}
    if "Retry-After" in respuesta.headers:
        encabezados["Retry-After"] = respuesta.headers["Retry-After"]
    return ErrorBff(
        estado,
        str(cuerpo.get("title") or "error_del_nucleo"),
        str(cuerpo.get("detail") or ""),
        detalles=cuerpo.get("detalles") or {},
        encabezados=encabezados,
    )


def segmento(valor: str, si_no_existe: str) -> str:
    """Un identificador del canal, apto para ir dentro de una ruta del núcleo.

    Sin `/`, `.`, `%` ni espacios: un valor como `..` no puede llevar la solicitud a otra ruta
    del núcleo. Lo que no tiene la forma de un identificador no existe, y responde el mismo
    404 del contrato que un identificador ajeno.
    """
    if _SEGMENTO.fullmatch(valor):
        return valor
    raise ErrorBff(status.HTTP_404_NOT_FOUND, si_no_existe, "El recurso no existe")


class ClienteNucleo:
    def __init__(
        self,
        url: str,
        tiempo_espera: float = 5.0,
        transporte: httpx.BaseTransport | None = None,
    ) -> None:
        self.url = url
        self._http = httpx.Client(base_url=url, timeout=tiempo_espera, transport=transporte)

    def solicitar(
        self,
        metodo: str,
        ruta: str,
        *,
        canal: str | None = None,
        json: object | None = None,
        params: Mapping[str, Any] | None = None,
        encabezados: Mapping[str, str] | None = None,
        archivos: Mapping[str, Any] | None = None,
        contenido: bytes | None = None,
        si_no_existe: str | None = None,
    ) -> httpx.Response:
        """Hace la solicitud y devuelve la respuesta si fue exitosa; si no, lanza `ErrorBff`.

        `canal` va en la cabecera `X-Canal`, que el núcleo deja en la auditoría.
        `contenido` es un cuerpo crudo, como el archivo de una evidencia, con su tipo en
        `encabezados`. `si_no_existe` reemplaza el código genérico de un 404 por el del
        contrato del canal (por ejemplo, `cotizacion_no_encontrada`).
        """
        cabeceras = dict(encabezados or {})
        if canal:
            cabeceras["X-Canal"] = canal
        try:
            respuesta = self._http.request(
                metodo,
                ruta,
                json=json,
                params=params,
                headers=cabeceras,
                files=archivos,
                content=contenido,
            )
        except httpx.TransportError as error:
            raise ErrorBff(
                status.HTTP_503_SERVICE_UNAVAILABLE,
                "nucleo_no_disponible",
                f"No se pudo llegar al núcleo: {type(error).__name__}",
            ) from error
        if respuesta.is_success:
            return respuesta
        error = _traducir(respuesta)
        if si_no_existe and error.estado == status.HTTP_404_NOT_FOUND:
            error.codigo = si_no_existe
        raise error

    def get(self, ruta: str, **opciones: Any) -> Any:
        return self.solicitar("GET", ruta, **opciones).json()

    def post(self, ruta: str, **opciones: Any) -> Any:
        respuesta = self.solicitar("POST", ruta, **opciones)
        return respuesta.json() if respuesta.content else None

    def estado(self) -> str:
        """Estado que reporta el `/salud` del núcleo, o `inalcanzable` si no responde."""
        try:
            respuesta = self._http.get("/salud", timeout=_ESPERA_SALUD)
            return str(respuesta.json().get("estado", "desconocido"))
        except (httpx.HTTPError, ValueError, AttributeError):
            return "inalcanzable"

    def cerrar(self) -> None:
        self._http.close()


def obtener_nucleo(request: Request) -> ClienteNucleo:
    """Dependencia de FastAPI: el cliente que armó `crear_app`."""
    return request.app.state.nucleo
