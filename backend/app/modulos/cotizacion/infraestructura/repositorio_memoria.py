"""Repositorio en memoria.

Implementación provisional del puerto de persistencia mientras llega el esquema
relacional del módulo (database per bounded context). Se sustituye por el repositorio
sobre PostgreSQL sin tocar dominio ni aplicación: solo cambia la inyección en el arranque.
"""

from __future__ import annotations

import itertools
import threading
from datetime import datetime

from app.modulos.cotizacion.dominio.modelo import Cotizacion, EstadoCotizacion

#: El prototipo arranca la numeración en QT-2026-08831.
PRIMER_CONSECUTIVO = 8831


class RepositorioEnMemoria:
    def __init__(self, primer_consecutivo: int = PRIMER_CONSECUTIVO) -> None:
        self._cotizaciones: dict[str, Cotizacion] = {}
        self._consecutivo = itertools.count(primer_consecutivo)
        self._candado = threading.Lock()

    def guardar(self, cotizacion: Cotizacion) -> None:
        with self._candado:
            self._cotizaciones[cotizacion.id_cotizacion] = cotizacion

    def obtener(self, id_cotizacion: str) -> Cotizacion | None:
        return self._cotizaciones.get(id_cotizacion)

    def vigente_por_huella(
        self, id_cliente: str, huella: str, ahora: datetime
    ) -> Cotizacion | None:
        with self._candado:
            candidatas = [
                c
                for c in self._cotizaciones.values()
                if c.id_cliente == id_cliente
                and c.huella == huella
                and c.estado(ahora) is EstadoCotizacion.VIGENTE
            ]
        return max(candidatas, key=lambda c: c.creada_en, default=None)

    def siguiente_id(self, anio: int) -> str:
        with self._candado:
            return f"QT-{anio}-{next(self._consecutivo):05d}"
