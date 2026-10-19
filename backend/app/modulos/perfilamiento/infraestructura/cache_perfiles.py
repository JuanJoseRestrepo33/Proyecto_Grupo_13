"""Caché de perfiles financieros.

Es el punto de sensibilidad de RC-04 que EXP-04 destapó: una revocación que se ve en la
interfaz pero cuyo perfil sigue sirviéndose desde la caché no es una revocación. Por eso
la caché se vacía por evento, no por expiración (corrección D-04), y hay una prueba que lo
verifica (WEB-F02, criterio 5).

El adaptador de Open Finance de ARQ-02 es quien la llena; este módulo solo garantiza que
se vacíe cuando debe.
"""

from __future__ import annotations

import threading


class CacheDePerfiles:
    def __init__(self) -> None:
        self._perfiles: dict[str, object] = {}
        self._cerrojo = threading.Lock()

    def guardar(self, id_cliente: str, perfil: object) -> None:
        with self._cerrojo:
            self._perfiles[id_cliente] = perfil

    def obtener(self, id_cliente: str) -> object | None:
        with self._cerrojo:
            return self._perfiles.get(id_cliente)

    def invalidar(self, id_cliente: str) -> bool:
        """Borra el perfil del cliente. Devuelve si había algo que borrar."""
        with self._cerrojo:
            return self._perfiles.pop(id_cliente, None) is not None

    @property
    def tamano(self) -> int:
        with self._cerrojo:
            return len(self._perfiles)
