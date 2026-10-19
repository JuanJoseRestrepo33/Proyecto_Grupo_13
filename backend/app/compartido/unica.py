"""Instancia única por proceso, creada al primer uso y a salvo de hilos.

Reemplaza a `functools.lru_cache(maxsize=1)` en la composición de los módulos. `lru_cache`
no impide que dos hilos ejecuten la fábrica a la vez: con el servidor recién levantado,
dos solicitudes simultáneas armaban dos servicios, cada uno con su repositorio en memoria,
y lo que se guardaba en el que no quedaba en caché se perdía. Aquí la fábrica corre una
sola vez, bajo cerrojo.
"""

from __future__ import annotations

import threading
from collections.abc import Callable
from functools import update_wrapper


class InstanciaUnica[T]:
    def __init__(self, fabrica: Callable[[], T]) -> None:
        self._fabrica = fabrica
        self._cerrojo = threading.Lock()
        self._creada = False
        self._valor: T | None = None
        update_wrapper(self, fabrica)

    def __call__(self) -> T:
        if not self._creada:
            with self._cerrojo:
                if not self._creada:
                    self._valor = self._fabrica()
                    self._creada = True
        return self._valor  # type: ignore[return-value]

    def cache_clear(self) -> None:
        """Descarta la instancia; la siguiente llamada crea otra. Solo para pruebas."""
        with self._cerrojo:
            self._creada = False
            self._valor = None


def instancia_unica[T](fabrica: Callable[[], T]) -> InstanciaUnica[T]:
    return InstanciaUnica(fabrica)
