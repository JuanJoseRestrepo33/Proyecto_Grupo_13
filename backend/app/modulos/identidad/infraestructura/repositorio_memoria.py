"""Repositorio en memoria. Mismo papel que el de cotización: desarrollo y pruebas."""

from __future__ import annotations

import threading

from ..dominio.modelo import VerificacionIdentidad


class RepositorioEnMemoria:
    def __init__(self) -> None:
        self._por_id: dict[str, VerificacionIdentidad] = {}
        self._cerrojo = threading.Lock()

    def guardar(self, verificacion: VerificacionIdentidad) -> None:
        with self._cerrojo:
            self._por_id[verificacion.id_verificacion] = verificacion

    def quitar(self, id_verificacion: str) -> None:
        with self._cerrojo:
            self._por_id.pop(id_verificacion, None)

    def ultima_del_cliente(self, id_cliente: str) -> VerificacionIdentidad | None:
        with self._cerrojo:
            propias = [v for v in self._por_id.values() if v.id_cliente == id_cliente]
        if not propias:
            return None
        return max(propias, key=lambda v: v.solicitada_en)
