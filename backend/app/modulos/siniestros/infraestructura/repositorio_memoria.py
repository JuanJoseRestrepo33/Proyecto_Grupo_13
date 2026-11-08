"""Repositorio de siniestros en memoria, para desarrollo local y pruebas.

La unicidad de la clave de idempotencia por cliente se sostiene bajo el mismo cerrojo que
el alta, igual que la restricción `UNIQUE` del repositorio en PostgreSQL. El cambio de estado
compara y cambia bajo el mismo cerrojo, como el `UPDATE … WHERE estado = …` de PostgreSQL.
"""

from __future__ import annotations

import itertools
import threading
from dataclasses import replace
from datetime import datetime

from ..dominio.evidencias import EvidenciaDelSiniestro
from ..dominio.modelo import EstadoSiniestro, Siniestro
from ..dominio.seguimiento import EventoDeSeguimiento, Requerimiento


class RepositorioEnMemoria:
    def __init__(self) -> None:
        self._por_id: dict[str, Siniestro] = {}
        self._evidencias: dict[str, list[EvidenciaDelSiniestro]] = {}
        self._eventos: dict[str, list[EventoDeSeguimiento]] = {}
        self._requerimientos: dict[str, list[Requerimiento]] = {}
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

    def agregar_evidencia(self, evidencia: EvidenciaDelSiniestro) -> None:
        with self._cerrojo:
            self._evidencias.setdefault(evidencia.id_siniestro, []).append(evidencia)

    def evidencias(self, id_siniestro: str) -> tuple[EvidenciaDelSiniestro, ...]:
        with self._cerrojo:
            return tuple(self._evidencias.get(id_siniestro, ()))

    # -- seguimiento (WEB-F06) ---------------------------------------------------------

    def por_id(self, id_siniestro: str) -> Siniestro | None:
        with self._cerrojo:
            return self._por_id.get(id_siniestro)

    def cambiar_estado(
        self,
        id_siniestro: str,
        anterior: EstadoSiniestro,
        nuevo: EstadoSiniestro,
        instante: datetime,
    ) -> bool:
        with self._cerrojo:
            siniestro = self._por_id.get(id_siniestro)
            if siniestro is None or siniestro.estado is not anterior:
                return False
            self._por_id[id_siniestro] = replace(
                siniestro, estado=nuevo, estado_actualizado_en=instante
            )
            return True

    def agregar_evento(self, evento: EventoDeSeguimiento) -> None:
        with self._cerrojo:
            self._eventos.setdefault(evento.id_siniestro, []).append(evento)

    def eventos(self, id_siniestro: str) -> tuple[EventoDeSeguimiento, ...]:
        with self._cerrojo:
            return tuple(self._eventos.get(id_siniestro, ()))

    def agregar_requerimientos(self, requerimientos: tuple[Requerimiento, ...]) -> None:
        with self._cerrojo:
            for requerimiento in requerimientos:
                self._requerimientos.setdefault(requerimiento.id_siniestro, []).append(
                    requerimiento
                )

    def requerimientos(self, id_siniestro: str) -> tuple[Requerimiento, ...]:
        with self._cerrojo:
            return tuple(self._requerimientos.get(id_siniestro, ()))

    def atender_requerimiento(
        self, id_requerimiento: str, id_evidencia: str, instante: datetime
    ) -> bool:
        with self._cerrojo:
            for lista in self._requerimientos.values():
                for i, requerimiento in enumerate(lista):
                    if requerimiento.id_requerimiento == id_requerimiento:
                        if not requerimiento.pendiente:
                            return False
                        lista[i] = replace(
                            requerimiento, atendido_en=instante, id_evidencia=id_evidencia
                        )
                        return True
        return False
