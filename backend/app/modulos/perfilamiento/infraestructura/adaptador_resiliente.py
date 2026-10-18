"""Adaptador de Open Finance con presupuesto, circuit breaker y caché (ARQ-02, criterio 5).

Es el camino que EXP-01 validó (p95 de 123 ms con el proveedor degradado):

1. Si el circuito está cerrado, se llama al proveedor con el presupuesto del camino del
   usuario (120 ms, D-01). Si responde, el perfil se guarda en caché y se devuelve.
2. Si falla o se pasa del presupuesto, se registra el fallo; con suficientes fallos en la
   ventana el circuito se abre (D-02).
3. Con el circuito abierto no se llama al proveedor: se sirve de caché, y cada cierto
   tiempo se lanza un sondeo en segundo plano con el tiempo límite largo (700 ms, D-03).
   Si el sondeo responde, el circuito se cierra.
4. Sin perfil en caché, la respuesta es "ninguno" y cotización tarifa a precio estándar
   en vez de dejar al cliente sin oferta (RC-03).
"""

from __future__ import annotations

import logging
import threading
import time
from collections.abc import Callable
from concurrent.futures import Future, ThreadPoolExecutor
from concurrent.futures import TimeoutError as FuturoVencido

from app.compartido.errores import DependenciaNoDisponible

from ..dominio.errores import ProveedorOpenFinanceNoDisponibleError
from ..dominio.modelo import Origen, PerfilDeRiesgo, RespuestaPerfil
from ..dominio.puertos import CacheDePerfiles, ProveedorOpenFinance
from .circuit_breaker import CircuitBreaker
from .metricas import Metricas

log = logging.getLogger(__name__)

PRESUPUESTO_USUARIO_S = 0.120  # D-01
TIEMPO_LIMITE_SONDEO_S = 0.700  # D-01


class AdaptadorOpenFinanceResiliente:
    def __init__(
        self,
        proveedor: ProveedorOpenFinance,
        cache: CacheDePerfiles,
        *,
        breaker: CircuitBreaker | None = None,
        metricas: Metricas | None = None,
        presupuesto_s: float = PRESUPUESTO_USUARIO_S,
        tiempo_limite_sondeo_s: float = TIEMPO_LIMITE_SONDEO_S,
        sondeo_en_segundo_plano: bool = True,
    ) -> None:
        self.proveedor = proveedor
        self.cache = cache
        self.breaker = breaker or CircuitBreaker()
        self.metricas = metricas or Metricas()
        self._presupuesto = presupuesto_s
        self._tiempo_sondeo = tiempo_limite_sondeo_s
        self._segundo_plano = sondeo_en_segundo_plano
        # Las llamadas al proveedor corren en hilos para poder abandonarlas al vencer el
        # presupuesto. El hilo abandonado termina solo; el usuario no lo espera.
        self._hilos = ThreadPoolExecutor(max_workers=8, thread_name_prefix="open-finance")
        self._hilo_sondeo: threading.Thread | None = None

    # --- camino del usuario ---------------------------------------------------------------

    def perfil_de(self, id_cliente: str, id_consentimiento: str) -> RespuestaPerfil:
        if self.breaker.permite():
            inicio = time.perf_counter()
            try:
                perfil = self._con_tiempo_limite(
                    lambda: self.proveedor.perfil_de(id_cliente, id_consentimiento),
                    self._presupuesto,
                )
            except DependenciaNoDisponible as error:
                self.metricas.registrar(time.perf_counter() - inicio, ok=False)
                self.breaker.registrar(False)
                log.warning("Open Finance no disponible para %s: %s", id_cliente, error.mensaje)
            else:
                self.metricas.registrar(time.perf_counter() - inicio, ok=True)
                self.breaker.registrar(True)
                self.cache.guardar(id_cliente, perfil)
                return RespuestaPerfil(perfil, Origen.PROVEEDOR)
        else:
            self._tal_vez_sondear()

        en_cache = self.cache.obtener(id_cliente)
        if isinstance(en_cache, PerfilDeRiesgo):
            return RespuestaPerfil(en_cache, Origen.CACHE)
        return RespuestaPerfil(None, Origen.NINGUNO)

    # --- sondeo de recuperación ----------------------------------------------------------

    def sondear(self) -> bool:
        """Pregunta al proveedor si volvió, con el tiempo límite largo. Cierra el circuito
        si responde. Devuelve el resultado."""
        try:
            self._con_tiempo_limite(self.proveedor.sondear, self._tiempo_sondeo)
        except DependenciaNoDisponible:
            self.breaker.resultado_sondeo(False)
            return False
        self.breaker.resultado_sondeo(True)
        log.info("Open Finance respondió al sondeo: circuito cerrado")
        return True

    def esperar_sondeo(self, tiempo_maximo_s: float = 2.0) -> None:
        """Para pruebas y apagado ordenado: espera el sondeo en curso, si lo hay."""
        hilo = self._hilo_sondeo
        if hilo is not None:
            hilo.join(tiempo_maximo_s)

    def _tal_vez_sondear(self) -> None:
        if not self.breaker.debe_sondear():
            return
        if not self._segundo_plano:
            self.sondear()
            return
        self._hilo_sondeo = threading.Thread(
            target=self.sondear, name="open-finance-sondeo", daemon=True
        )
        self._hilo_sondeo.start()

    # --- internos -----------------------------------------------------------------------

    def _con_tiempo_limite(self, llamada: Callable[[], object], limite_s: float) -> object:
        futuro: Future[object] = self._hilos.submit(llamada)
        try:
            return futuro.result(timeout=limite_s)
        except FuturoVencido:
            raise ProveedorOpenFinanceNoDisponibleError(
                "El proveedor de Open Finance excedió el tiempo límite",
                limite_ms=round(limite_s * 1000),
            ) from None
        except DependenciaNoDisponible:
            raise
        except Exception as error:
            # Cualquier otra falla del cliente del proveedor se traduce al dominio.
            raise ProveedorOpenFinanceNoDisponibleError(
                "El proveedor de Open Finance falló", causa=type(error).__name__
            ) from error

    def resumen(self) -> dict[str, object]:
        return {
            "circuito": self.breaker.resumen(),
            "latencia_y_errores": self.metricas.resumen(),
            "presupuesto_ms": round(self._presupuesto * 1000),
            "tiempo_limite_sondeo_ms": round(self._tiempo_sondeo * 1000),
        }
