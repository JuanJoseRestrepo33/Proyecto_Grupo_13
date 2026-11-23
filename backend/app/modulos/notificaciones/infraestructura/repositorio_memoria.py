"""Repositorios de dispositivos y notificaciones en memoria, para desarrollo y pruebas."""

from __future__ import annotations

import threading
from dataclasses import replace
from datetime import datetime

from ..dominio.modelo import Dispositivo, Notificacion


class DispositivosEnMemoria:
    def __init__(self) -> None:
        self._por_id: dict[str, Dispositivo] = {}
        self._cerrojo = threading.Lock()

    def registrar(self, dispositivo: Dispositivo) -> tuple[Dispositivo, bool]:
        with self._cerrojo:
            existente = self._por_id.get(dispositivo.id_dispositivo)
            if existente is None:
                self._por_id[dispositivo.id_dispositivo] = dispositivo
                return dispositivo, True
            actualizado = replace(
                existente,
                id_cliente=dispositivo.id_cliente,
                plataforma=dispositivo.plataforma,
                actualizado_en=dispositivo.actualizado_en,
            )
            self._por_id[dispositivo.id_dispositivo] = actualizado
            return actualizado, False

    def dar_de_baja(self, id_cliente: str, id_dispositivo: str) -> bool:
        with self._cerrojo:
            existente = self._por_id.get(id_dispositivo)
            if existente is None or existente.id_cliente != id_cliente:
                return False
            del self._por_id[id_dispositivo]
            return True

    def del_cliente(self, id_cliente: str) -> tuple[Dispositivo, ...]:
        with self._cerrojo:
            return tuple(d for d in self._por_id.values() if d.id_cliente == id_cliente)


class NotificacionesEnMemoria:
    def __init__(self) -> None:
        self._por_id: dict[str, Notificacion] = {}
        self._cerrojo = threading.Lock()

    def guardar_si_nueva(self, notificacion: Notificacion) -> tuple[Notificacion, bool]:
        with self._cerrojo:
            for existente in self._por_id.values():
                if (
                    existente.id_evento == notificacion.id_evento
                    and existente.id_cliente == notificacion.id_cliente
                ):
                    return existente, False
            self._por_id[notificacion.id_notificacion] = notificacion
            return notificacion, True

    def actualizar(self, notificacion: Notificacion) -> None:
        with self._cerrojo:
            self._por_id[notificacion.id_notificacion] = notificacion

    def del_cliente(self, id_cliente: str, limite: int) -> tuple[Notificacion, ...]:
        # Los dict conservan el orden de inserción: el más reciente es el último.
        with self._cerrojo:
            propias = [n for n in self._por_id.values() if n.id_cliente == id_cliente]
        return tuple(reversed(propias))[:limite]

    def marcar_leida(self, id_cliente: str, id_notificacion: str, instante: datetime) -> bool:
        with self._cerrojo:
            existente = self._por_id.get(id_notificacion)
            if existente is None or existente.id_cliente != id_cliente:
                return False
            if existente.leida_en is None:
                self._por_id[id_notificacion] = replace(existente, leida_en=instante)
            return True
