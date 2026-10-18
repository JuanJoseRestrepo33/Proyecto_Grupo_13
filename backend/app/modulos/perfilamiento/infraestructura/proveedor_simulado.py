"""Proveedor de Open Finance simulado (ARQ-02, criterio 2: determinista).

Tres modos, que son los tres escenarios de WireMock de EXP-01:

- `sano`: responde de inmediato.
- `degradado`: responde, pero tarda más que el presupuesto del camino del usuario. El
  adaptador corta y sirve de caché.
- `caido`: falla siempre, con el error tipado del dominio.

El perfil es fijo por cliente, para que una prueba pueda afirmar sobre él. Es el mismo
que daba el adaptador simulado de cotización, así que ninguna prima cambia.
"""

from __future__ import annotations

import threading
import time
from enum import StrEnum

from ..dominio.errores import ProveedorOpenFinanceNoDisponibleError
from ..dominio.modelo import PerfilDeRiesgo

NOMBRE = "open-finance-simulado"

PERFIL_POR_DEFECTO = PerfilDeRiesgo(
    puntaje=720, comportamiento_pago="al dia", nivel_endeudamiento="moderado"
)
PERFILES: dict[str, PerfilDeRiesgo] = {
    "CLI-0002": PerfilDeRiesgo(puntaje=610, comportamiento_pago="mora", nivel_endeudamiento="alto"),
}


class Modo(StrEnum):
    SANO = "sano"
    DEGRADADO = "degradado"
    CAIDO = "caido"


class ProveedorOpenFinanceSimulado:
    def __init__(self, modo: Modo | str = Modo.SANO, latencia_degradado_s: float = 0.25) -> None:
        self.modo = Modo(modo)
        self.latencia_degradado_s = latencia_degradado_s
        self._llamadas = 0
        self._sondeos = 0
        self._cerrojo = threading.Lock()

    @property
    def llamadas(self) -> int:
        with self._cerrojo:
            return self._llamadas

    @property
    def sondeos(self) -> int:
        with self._cerrojo:
            return self._sondeos

    def _responder(self) -> None:
        if self.modo is Modo.CAIDO:
            raise ProveedorOpenFinanceNoDisponibleError(
                "El proveedor de Open Finance no responde", proveedor=NOMBRE
            )
        if self.modo is Modo.DEGRADADO:
            time.sleep(self.latencia_degradado_s)

    def perfil_de(self, id_cliente: str, id_consentimiento: str) -> PerfilDeRiesgo:
        del id_consentimiento  # el simulado no lo valida; el real lo exige en cada llamada
        with self._cerrojo:
            self._llamadas += 1
        self._responder()
        return PERFILES.get(id_cliente, PERFIL_POR_DEFECTO)

    def sondear(self) -> None:
        with self._cerrojo:
            self._sondeos += 1
        self._responder()
