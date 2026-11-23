"""Puertos del módulo de notificaciones.

RepositorioDispositivos   → dispositivos registrados de cada cliente
RepositorioNotificaciones → notificaciones enviadas, que la app también puede consultar
ProveedorPush             → proveedor externo de push (simulado en este prototipo)
"""

from __future__ import annotations

from collections.abc import Mapping
from datetime import datetime
from typing import Protocol

from .modelo import Dispositivo, Notificacion


class RepositorioDispositivos(Protocol):
    def registrar(self, dispositivo: Dispositivo) -> tuple[Dispositivo, bool]:
        """Guarda el dispositivo por su id. Si ya existía, actualiza el cliente y la fecha y
        conserva la de registro. Devuelve el dispositivo y si es nuevo."""
        raise NotImplementedError

    def dar_de_baja(self, id_cliente: str, id_dispositivo: str) -> bool:
        """Lo borra si es del cliente. Devuelve si había algo que borrar."""
        raise NotImplementedError

    def del_cliente(self, id_cliente: str) -> tuple[Dispositivo, ...]:
        raise NotImplementedError


class RepositorioNotificaciones(Protocol):
    def guardar_si_nueva(self, notificacion: Notificacion) -> tuple[Notificacion, bool]:
        """La guarda salvo que ya exista una del mismo evento y cliente. Es atómico."""
        raise NotImplementedError

    def actualizar(self, notificacion: Notificacion) -> None:
        raise NotImplementedError

    def del_cliente(self, id_cliente: str, limite: int) -> tuple[Notificacion, ...]:
        """De la más reciente a la más antigua."""
        raise NotImplementedError

    def marcar_leida(self, id_cliente: str, id_notificacion: str, instante: datetime) -> bool:
        """Devuelve si la notificación existe y es del cliente."""
        raise NotImplementedError


class ProveedorPush(Protocol):
    def enviar(self, token: str, titulo: str, cuerpo: str, datos: Mapping[str, str]) -> str:
        """Envía el push y devuelve el id del mensaje en el proveedor. Lanza
        `DependenciaNoDisponible` si el proveedor no lo acepta."""
        raise NotImplementedError
