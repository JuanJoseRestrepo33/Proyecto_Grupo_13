"""Métricas de latencia y de tasa de error del adaptador (ARQ-02, criterio 5)."""

from __future__ import annotations

import threading
from collections import deque


class Metricas:
    def __init__(self, memoria: int = 1000) -> None:
        self._latencias_ms: deque[float] = deque(maxlen=memoria)
        self._total = 0
        self._errores = 0
        self._cerrojo = threading.Lock()

    def registrar(self, latencia_s: float, ok: bool) -> None:
        with self._cerrojo:
            self._latencias_ms.append(latencia_s * 1000)
            self._total += 1
            if not ok:
                self._errores += 1

    def p95_ms(self) -> float:
        with self._cerrojo:
            if not self._latencias_ms:
                return 0.0
            ordenadas = sorted(self._latencias_ms)
            indice = min(len(ordenadas) - 1, int(round(0.95 * (len(ordenadas) - 1))))
            return round(ordenadas[indice], 1)

    def tasa_error(self) -> float:
        with self._cerrojo:
            return round(self._errores / self._total, 3) if self._total else 0.0

    def resumen(self) -> dict[str, object]:
        with self._cerrojo:
            total, errores = self._total, self._errores
        return {
            "llamadas": total,
            "errores": errores,
            "tasa_error": round(errores / total, 3) if total else 0.0,
            "p95_ms": self.p95_ms(),
        }
