"""Repositorio de siniestros en memoria, para desarrollo local y pruebas.

La unicidad de la clave de idempotencia por cliente se sostiene bajo el mismo cerrojo que
el alta, igual que la restricción `UNIQUE` del repositorio en PostgreSQL. El cambio de estado
y la respuesta a un requerimiento comparan y escriben todo bajo el mismo cerrojo, como la
transacción de PostgreSQL: o queda todo o no queda nada.
"""

from __future__ import annotations

import itertools
import threading
from dataclasses import replace

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

    def aplicar_cambio_de_estado(
        self,
        anterior: EstadoSiniestro,
        evento: EventoDeSeguimiento,
        requerimientos: tuple[Requerimiento, ...],
    ) -> bool:
        with self._cerrojo:
            siniestro = self._por_id.get(evento.id_siniestro)
            if siniestro is None or siniestro.estado is not anterior:
                return False
            instante = evento.ocurrido_en
            self._por_id[evento.id_siniestro] = replace(
                siniestro, estado=evento.estado_nuevo, estado_actualizado_en=instante
            )
            self._eventos.setdefault(evento.id_siniestro, []).append(evento)
            lista = self._requerimientos.setdefault(evento.id_siniestro, [])
            lista[:] = [r if not r.pendiente else replace(r, cerrado_en=instante) for r in lista]
            lista.extend(requerimientos)
            return True

    def eventos(self, id_siniestro: str) -> tuple[EventoDeSeguimiento, ...]:
        with self._cerrojo:
            return tuple(self._eventos.get(id_siniestro, ()))

    def requerimientos(self, id_siniestro: str) -> tuple[Requerimiento, ...]:
        with self._cerrojo:
            return tuple(self._requerimientos.get(id_siniestro, ()))

    def responder_requerimiento(
        self, evidencia: EvidenciaDelSiniestro, id_requerimiento: str
    ) -> bool:
        with self._cerrojo:
            lista = self._requerimientos.get(evidencia.id_siniestro, [])
            for i, requerimiento in enumerate(lista):
                if requerimiento.id_requerimiento == id_requerimiento:
                    if not requerimiento.pendiente:
                        return False
                    lista[i] = replace(
                        requerimiento,
                        atendido_en=evidencia.cargada_en,
                        id_evidencia=evidencia.id_evidencia,
                    )
                    self._evidencias.setdefault(evidencia.id_siniestro, []).append(evidencia)
                    return True
        return False
