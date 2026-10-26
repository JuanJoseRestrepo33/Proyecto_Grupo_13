"""Repositorio de pólizas en memoria. Lo llena la semilla de *staging* al arrancar."""

from __future__ import annotations

import threading

from ..dominio.modelo import Poliza


class RepositorioEnMemoria:
    def __init__(self) -> None:
        self._por_numero: dict[str, Poliza] = {}
        self._cerrojo = threading.Lock()

    def guardar(self, poliza: Poliza) -> None:
        with self._cerrojo:
            self._por_numero[poliza.numero] = poliza

    def por_numero(self, numero: str) -> Poliza | None:
        return self._por_numero.get(numero)

    def del_cliente(self, id_cliente: str) -> tuple[Poliza, ...]:
        with self._cerrojo:
            return tuple(p for p in self._por_numero.values() if p.id_cliente == id_cliente)
