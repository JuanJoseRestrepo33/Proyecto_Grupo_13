"""Circuit breaker del adaptador de Open Finance.

Es el de EXP-01 llevado al núcleo, con sus tres correcciones:

- D-01: el presupuesto corto (120 ms) es el del camino del usuario; los 700 ms son solo
  para el sondeo. Eso lo aplica el adaptador; aquí solo se decide si se permite llamar.
- D-02: el circuito no se evalúa con menos de 5 llamadas en la ventana. Sin ese mínimo,
  un solo fallo al arrancar abría el circuito.
- D-03: el sondeo de recuperación no bloquea al usuario. Aquí se decide cuándo sondear;
  el adaptador lo corre en segundo plano.

Estados: CERRADO (se llama al proveedor) → ABIERTO (se sirve de caché) → sondeo →
CERRADO si el sondeo responde.
"""

from __future__ import annotations

import threading
import time
from collections.abc import Callable
from enum import StrEnum


class EstadoCircuito(StrEnum):
    CERRADO = "cerrado"
    ABIERTO = "abierto"


class CircuitBreaker:
    def __init__(
        self,
        *,
        umbral_fallos: float = 0.10,
        ventana_s: float = 30.0,
        minimo_llamadas: int = 5,
        sondear_tras_s: float = 10.0,
        reloj_monotono: Callable[[], float] = time.monotonic,
    ) -> None:
        self._umbral = umbral_fallos
        self._ventana = ventana_s
        self._minimo = minimo_llamadas
        self._sondear_tras = sondear_tras_s
        self._ahora = reloj_monotono
        self._cerrojo = threading.Lock()
        self._estado = EstadoCircuito.CERRADO
        self._eventos: list[tuple[float, bool]] = []
        self._abierto_desde: float | None = None
        self._sondeando = False
        self.aperturas = 0
        self.cierres = 0

    @property
    def estado(self) -> EstadoCircuito:
        with self._cerrojo:
            return self._estado

    def permite(self) -> bool:
        with self._cerrojo:
            return self._estado is EstadoCircuito.CERRADO

    def registrar(self, ok: bool) -> None:
        with self._cerrojo:
            ahora = self._ahora()
            self._eventos.append((ahora, ok))
            self._eventos = [e for e in self._eventos if ahora - e[0] <= self._ventana]
            if self._estado is not EstadoCircuito.CERRADO or len(self._eventos) < self._minimo:
                return
            fallos = sum(1 for _, exito in self._eventos if not exito)
            if fallos / len(self._eventos) >= self._umbral:
                self._estado = EstadoCircuito.ABIERTO
                self._abierto_desde = ahora
                self.aperturas += 1

    def debe_sondear(self) -> bool:
        """True una sola vez por periodo: quien la recibe queda a cargo del sondeo."""
        with self._cerrojo:
            if self._estado is not EstadoCircuito.ABIERTO or self._sondeando:
                return False
            if (
                self._abierto_desde is None
                or self._ahora() - self._abierto_desde < self._sondear_tras
            ):
                return False
            self._sondeando = True
            return True

    def resultado_sondeo(self, ok: bool) -> None:
        with self._cerrojo:
            self._sondeando = False
            if ok:
                self._estado = EstadoCircuito.CERRADO
                self._eventos = []
                self._abierto_desde = None
                self.cierres += 1
            else:
                self._abierto_desde = self._ahora()

    def resumen(self) -> dict[str, object]:
        with self._cerrojo:
            total = len(self._eventos)
            fallos = sum(1 for _, exito in self._eventos if not exito)
            return {
                "estado": str(self._estado),
                "llamadas_en_ventana": total,
                "tasa_error_en_ventana": round(fallos / total, 3) if total else 0.0,
                "aperturas": self.aperturas,
                "cierres": self.cierres,
            }
