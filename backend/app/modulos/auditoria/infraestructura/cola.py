"""Cola de escritura en segundo plano.

El log de auditoría no debe cargar la latencia del camino del usuario: EXP-04 midió
0,81 ms por escritura síncrona frente a 0,01 ms encolando. Un único hilo consume la cola
en orden, de modo que las decisiones quedan encadenadas en el orden en que se tomaron.

Lo que se pierde a cambio: un fallo al escribir no llega al llamador, solo al log de la
aplicación. Es una decisión consciente y está documentada en el README.
"""

from __future__ import annotations

import logging
import queue
import threading
from collections.abc import Callable

log = logging.getLogger(__name__)

Tarea = Callable[[], object]


class ColaDeEscritura:
    def __init__(self) -> None:
        self._cola: queue.Queue[Tarea] = queue.Queue()
        self._hilo = threading.Thread(
            target=self._trabajar, name="auditoria-escritura", daemon=True
        )
        self._hilo.start()

    def __call__(self, tarea: Tarea) -> None:
        self._cola.put(tarea)

    def vaciar(self) -> None:
        """Espera a que todo lo encolado quede escrito. Para pruebas y para apagar."""
        self._cola.join()

    @property
    def pendientes(self) -> int:
        return self._cola.qsize()

    def _trabajar(self) -> None:
        while True:
            tarea = self._cola.get()
            try:
                tarea()
            except Exception:
                log.exception("No se pudo escribir un registro de auditoría")
            finally:
                self._cola.task_done()
