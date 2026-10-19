"""Repositorio en memoria. Mismo papel que el de cotización: desarrollo y pruebas."""

from __future__ import annotations

import threading

from ..dominio.modelo import Consentimiento


class RepositorioEnMemoria:
    def __init__(self) -> None:
        self._por_id: dict[str, Consentimiento] = {}
        self._cerrojo = threading.Lock()

    def guardar(self, consentimiento: Consentimiento) -> None:
        with self._cerrojo:
            self._por_id[consentimiento.id_consentimiento] = consentimiento

    def obtener(self, id_consentimiento: str) -> Consentimiento | None:
        with self._cerrojo:
            return self._por_id.get(id_consentimiento)

    def del_cliente(self, id_cliente: str) -> tuple[Consentimiento, ...]:
        with self._cerrojo:
            propios = [c for c in self._por_id.values() if c.id_cliente == id_cliente]
        return tuple(sorted(propios, key=lambda c: c.otorgado_en))
