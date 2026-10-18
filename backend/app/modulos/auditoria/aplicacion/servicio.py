"""Casos de uso del log de auditoría: registrar, reconstruir y verificar."""

from __future__ import annotations

import uuid
from collections.abc import Callable

from app.compartido.errores import RecursoNoEncontrado

from ..dominio import cadena
from ..dominio.modelo import EventoAuditable, InformeDeIntegridad, Registro
from ..dominio.puertos import AlmacenDeRegistros

Tarea = Callable[[], object]
Ejecutor = Callable[[Tarea], None]


def _ejecutar_en_linea(tarea: Tarea) -> None:
    tarea()


class ServicioDeAuditoria:
    """Fachada de aplicación del módulo.

    `ejecutor` decide si la escritura ocurre en el hilo del llamador o se difiere. En
    producción se difiere, para que el log no cargue la latencia de la cotización; el
    identificador de la decisión se devuelve de inmediato porque se asigna aquí y no en
    la base.
    """

    def __init__(self, almacen: AlmacenDeRegistros, ejecutor: Ejecutor | None = None) -> None:
        self._almacen = almacen
        self._ejecutar = ejecutor or _ejecutar_en_linea

    def registrar(self, evento: EventoAuditable) -> str:
        id_decision = f"DEC-{uuid.uuid4()}"
        self._ejecutar(lambda: self._almacen.anexar(id_decision, evento))
        return id_decision

    def reconstruir(self, id_decision: str) -> Registro:
        """Devuelve la decisión tal como quedó registrada (RC-08)."""
        registro = self._almacen.obtener(id_decision)
        if registro is None:
            raise RecursoNoEncontrado(
                f"No existe la decisión {id_decision}", id_decision=id_decision
            )
        return registro

    def verificar_integridad(self) -> InformeDeIntegridad:
        return cadena.verificar(self._almacen.todos())
