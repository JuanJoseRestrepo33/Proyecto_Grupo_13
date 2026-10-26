"""Repositorio de siniestros en memoria, para desarrollo local y pruebas.

La unicidad de la clave de idempotencia por cliente se sostiene bajo el mismo cerrojo que
el alta, igual que la restricción `UNIQUE` del repositorio en PostgreSQL.
"""

from __future__ import annotations

import itertools
import threading

from ..dominio.modelo import Siniestro


class RepositorioEnMemoria:
    def __init__(self) -> None:
        self._por_id: dict[str, Siniestro] = {}
        self._consecutivo = itertools.count(1)
        self._cerrojo = threading.Lock()

    def guardar_si_nuevo(self, siniestro: Siniestro) -> Siniestro:
        with self._cerrojo:
            if siniestro.clave_idempotencia is not None:
                for existente in self._por_id.values():
                    if (
                        existente.id_cliente == siniestro.id_cliente
                        and existente.clave_idempotencia == siniestro.clave_idempotencia
                    ):
                        return existente
            self._por_id[siniestro.id_siniestro] = siniestro
            return siniestro

    def obtener(self, id_cliente: str, id_siniestro: str) -> Siniestro | None:
        siniestro = self._por_id.get(id_siniestro)
        return siniestro if siniestro and siniestro.id_cliente == id_cliente else None

    def del_cliente(self, id_cliente: str) -> tuple[Siniestro, ...]:
        with self._cerrojo:
            propios = [s for s in self._por_id.values() if s.id_cliente == id_cliente]
        return tuple(sorted(propios, key=lambda s: s.reportado_en, reverse=True))

    def siguiente_referencia(self, anio: int) -> str:
        with self._cerrojo:
            return f"SIN-{anio}-{next(self._consecutivo):06d}"
