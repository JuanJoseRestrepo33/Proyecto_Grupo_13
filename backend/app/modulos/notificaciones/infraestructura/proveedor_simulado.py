"""Proveedor de push simulado (integraciones externas simuladas).

Hace las veces de Firebase Cloud Messaging: acepta el mensaje y lo deja en un buzón en
memoria, sin salir a la red. La app no recibe un push real del sistema operativo: lee sus
notificaciones con `GET /api/v1/movil/notificaciones` y las muestra como push local (ver
`contratos/bff-movil`). Cambiar a FCM real es implementar este puerto, sin tocar el resto.

`PUSH_MODO=caido` hace que rechace todo envío, para probar que un cambio de estado no se
cae con el proveedor caído y que la notificación queda `fallida`.
"""

from __future__ import annotations

import itertools
import threading
from collections.abc import Mapping
from dataclasses import dataclass

from app.compartido.errores import DependenciaNoDisponible


@dataclass(frozen=True)
class MensajeEnviado:
    id_mensaje: str
    token: str
    titulo: str
    cuerpo: str
    datos: Mapping[str, str]


class ProveedorPushSimulado:
    def __init__(self, caido: bool = False) -> None:
        self.caido = caido
        self.enviados: list[MensajeEnviado] = []
        self._consecutivo = itertools.count(1)
        self._cerrojo = threading.Lock()

    @classmethod
    def desde_entorno(cls, entorno: Mapping[str, str]) -> ProveedorPushSimulado:
        modo = entorno.get("PUSH_MODO", "sano")
        if modo not in ("sano", "caido"):
            raise RuntimeError(f"PUSH_MODO no admite {modo!r}: usa sano o caido")
        return cls(caido=modo == "caido")

    def enviar(self, token: str, titulo: str, cuerpo: str, datos: Mapping[str, str]) -> str:
        if self.caido:
            raise DependenciaNoDisponible("El proveedor de push no respondió", proveedor="push")
        with self._cerrojo:
            id_mensaje = f"MSG-{next(self._consecutivo):08d}"
            self.enviados.append(MensajeEnviado(id_mensaje, token, titulo, cuerpo, dict(datos)))
        return id_mensaje
