"""Repositorio de pólizas en memoria. Lo llena la semilla de *staging* al arrancar.

La unicidad de la póliza por suscripción se sostiene bajo el mismo cerrojo que el alta,
igual que la restricción `UNIQUE` del repositorio en PostgreSQL.
"""

from __future__ import annotations

import itertools
import threading

from ..dominio.modelo import Poliza


class RepositorioEnMemoria:
    def __init__(self) -> None:
        self._por_numero: dict[str, Poliza] = {}
        self._consecutivo = itertools.count(1)
        self._cerrojo = threading.Lock()

    def guardar(self, poliza: Poliza) -> None:
        with self._cerrojo:
            self._por_numero[poliza.numero] = poliza

    def emitir_si_nueva(self, poliza: Poliza) -> Poliza:
        with self._cerrojo:
            for existente in self._por_numero.values():
                if existente.id_suscripcion == poliza.id_suscripcion:
                    return existente
            self._por_numero[poliza.numero] = poliza
            return poliza

    def por_numero(self, numero: str) -> Poliza | None:
        with self._cerrojo:
            return self._por_numero.get(numero)

    def por_suscripcion(self, id_suscripcion: str) -> Poliza | None:
        with self._cerrojo:
            return next(
                (p for p in self._por_numero.values() if p.id_suscripcion == id_suscripcion),
                None,
            )

    def del_cliente(self, id_cliente: str) -> tuple[Poliza, ...]:
        with self._cerrojo:
            return tuple(p for p in self._por_numero.values() if p.id_cliente == id_cliente)

    def siguiente_numero(self, anio: int) -> str:
        with self._cerrojo:
            return f"POL-{anio}-{next(self._consecutivo):06d}"
