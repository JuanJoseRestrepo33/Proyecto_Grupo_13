"""Repositorio de suscripciones en memoria, para desarrollo local y pruebas.

La unicidad de la suscripción por cotización se sostiene bajo el mismo cerrojo que el alta,
igual que la restricción `UNIQUE` del repositorio en PostgreSQL.
"""

from __future__ import annotations

import threading
from dataclasses import replace

from ..dominio.modelo import Suscripcion


class RepositorioEnMemoria:
    def __init__(self) -> None:
        self._por_id: dict[str, Suscripcion] = {}
        self._cerrojo = threading.Lock()

    def guardar_si_nueva(self, suscripcion: Suscripcion) -> Suscripcion:
        with self._cerrojo:
            for existente in self._por_id.values():
                if existente.id_cotizacion == suscripcion.id_cotizacion:
                    return existente
            self._por_id[suscripcion.id_suscripcion] = suscripcion
            return suscripcion

    def actualizar(self, suscripcion: Suscripcion) -> Suscripcion | None:
        with self._cerrojo:
            guardada = self._por_id.get(suscripcion.id_suscripcion)
            if guardada is None or guardada.version != suscripcion.version:
                return None
            nueva = replace(suscripcion, version=suscripcion.version + 1)
            self._por_id[suscripcion.id_suscripcion] = nueva
            return nueva

    def obtener(self, id_suscripcion: str) -> Suscripcion | None:
        with self._cerrojo:
            return self._por_id.get(id_suscripcion)

    def por_cotizacion(self, id_cotizacion: str) -> Suscripcion | None:
        with self._cerrojo:
            return next(
                (s for s in self._por_id.values() if s.id_cotizacion == id_cotizacion), None
            )

    def del_cliente(self, id_cliente: str) -> tuple[Suscripcion, ...]:
        with self._cerrojo:
            propias = [s for s in self._por_id.values() if s.id_cliente == id_cliente]
        return tuple(sorted(propias, key=lambda s: s.aceptada_en, reverse=True))
