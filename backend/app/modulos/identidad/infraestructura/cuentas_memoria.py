"""Cuentas, registros y tokens de refresco en memoria: desarrollo, pruebas y la demo.

Se pierden al reiniciar el proceso. Por eso la cuenta de prueba de *staging* se siembra al
arrancar (ver `api/dependencias.py`) en vez de registrarse a mano.
"""

from __future__ import annotations

import itertools
import threading
import time

from ..dominio.cuenta import Cuenta, Registro
from ..dominio.sesion import Refresco


class RepositorioDeCuentasEnMemoria:
    def __init__(self, inicio: int | None = None) -> None:
        """El consecutivo arranca en la hora de inicio, no en 1: las cuentas se pierden con
        cada revisión, pero un id ya entregado no puede volver a usarse."""
        self._por_id: dict[str, Cuenta] = {}
        self._consecutivo = itertools.count(int(time.time()) if inicio is None else inicio)
        self._cerrojo = threading.Lock()

    def guardar(self, cuenta: Cuenta) -> None:
        with self._cerrojo:
            self._por_id[cuenta.id_cliente] = cuenta

    def por_id(self, id_cliente: str) -> Cuenta | None:
        return self._por_id.get(id_cliente)

    def por_correo(self, correo: str) -> Cuenta | None:
        with self._cerrojo:
            return next((c for c in self._por_id.values() if c.correo == correo), None)

    def por_documento(self, numero_documento: str) -> Cuenta | None:
        with self._cerrojo:
            return next(
                (c for c in self._por_id.values() if c.numero_documento == numero_documento), None
            )

    def siguiente_id(self, anio: int) -> str:
        with self._cerrojo:
            return f"CLI-{anio}-{next(self._consecutivo):06d}"


class RepositorioDeRegistrosEnMemoria:
    def __init__(self) -> None:
        self._por_id: dict[str, Registro] = {}
        self._consecutivo = itertools.count(1)
        self._cerrojo = threading.Lock()

    def guardar(self, registro: Registro) -> None:
        with self._cerrojo:
            self._por_id[registro.id_registro] = registro

    def obtener(self, id_registro: str) -> Registro | None:
        return self._por_id.get(id_registro)

    def siguiente_id(self, anio: int) -> str:
        with self._cerrojo:
            return f"REG-{anio}-{next(self._consecutivo):06d}"


class RepositorioDeRefrescosEnMemoria:
    def __init__(self) -> None:
        self._por_resumen: dict[str, Refresco] = {}
        self._cerrojo = threading.Lock()

    def guardar(self, refresco: Refresco) -> None:
        with self._cerrojo:
            self._por_resumen[refresco.resumen] = refresco

    def tomar(self, resumen: str) -> Refresco | None:
        with self._cerrojo:
            return self._por_resumen.pop(resumen, None)
