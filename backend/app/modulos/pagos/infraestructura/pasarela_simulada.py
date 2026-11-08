"""Pasarela de pagos simulada (integraciones externas simuladas, ver `docs/devops.md`).

Responde como los mocks de la web (`apps/web/src/mocks`): la tarjeta se aprueba y PSE queda
`pendiente` hasta que se consulta, cuando el banco "confirma". Cada medio se puede forzar
por configuración para que las pruebas de extremo a extremo vean los tres estados:

    PASARELA_TARJETA_CREDITO = aprobado | rechazado | pendiente   (por defecto, aprobado)
    PASARELA_PSE             = aprobado | rechazado | pendiente   (por defecto, pendiente)
    PASARELA_PENDIENTE_SE_RESUELVE_EN = aprobado | rechazado      (por defecto, aprobado)
    PASARELA_MODO            = sana | caida                        (por defecto, sana)

Con `caida` la pasarela no responde y no cobra: el núcleo responde 503 y el cliente puede
reintentar con la misma clave.
"""

from __future__ import annotations

import itertools
import threading
from collections.abc import Mapping

from app.compartido.dinero import Dinero
from app.compartido.errores import DependenciaNoDisponible

from ..dominio.modelo import EstadoPago, MedioPago, RespuestaPasarela

POR_DEFECTO = {MedioPago.TARJETA_CREDITO: EstadoPago.APROBADO, MedioPago.PSE: EstadoPago.PENDIENTE}
_RESULTADOS = {EstadoPago.APROBADO, EstadoPago.RECHAZADO, EstadoPago.PENDIENTE}


def _estado(valor: str | None, por_defecto: EstadoPago, nombre: str) -> EstadoPago:
    if not valor:
        return por_defecto
    try:
        estado = EstadoPago(valor)
    except ValueError:
        estado = None
    if estado not in _RESULTADOS:
        raise RuntimeError(f"{nombre} no admite {valor!r}: usa aprobado, rechazado o pendiente")
    return estado  # type: ignore[return-value]


class PasarelaSimulada:
    def __init__(
        self,
        resultados: Mapping[MedioPago, EstadoPago] | None = None,
        pendiente_se_resuelve_en: EstadoPago = EstadoPago.APROBADO,
        caida: bool = False,
    ) -> None:
        self.resultados = dict(POR_DEFECTO | dict(resultados or {}))
        self.pendiente_se_resuelve_en = pendiente_se_resuelve_en
        self.caida = caida
        self.cobros: list[tuple[str, Dinero, MedioPago]] = []
        self._transacciones: dict[str, EstadoPago] = {}
        #: Referencia de la transacción de cada pago, para conciliar por nuestro id.
        self._por_pago: dict[str, str] = {}
        self._consecutivo = itertools.count(77_120_418)
        self._cerrojo = threading.Lock()

    @classmethod
    def desde_entorno(cls, entorno: Mapping[str, str]) -> PasarelaSimulada:
        modo = entorno.get("PASARELA_MODO", "sana")
        if modo not in ("sana", "caida"):
            raise RuntimeError(f"PASARELA_MODO no admite {modo!r}: usa sana o caida")
        return cls(
            resultados={
                MedioPago.TARJETA_CREDITO: _estado(
                    entorno.get("PASARELA_TARJETA_CREDITO"),
                    POR_DEFECTO[MedioPago.TARJETA_CREDITO],
                    "PASARELA_TARJETA_CREDITO",
                ),
                MedioPago.PSE: _estado(
                    entorno.get("PASARELA_PSE"), POR_DEFECTO[MedioPago.PSE], "PASARELA_PSE"
                ),
            },
            pendiente_se_resuelve_en=_estado(
                entorno.get("PASARELA_PENDIENTE_SE_RESUELVE_EN"),
                EstadoPago.APROBADO,
                "PASARELA_PENDIENTE_SE_RESUELVE_EN",
            ),
            caida=modo == "caida",
        )

    def cobrar(self, id_pago: str, monto: Dinero, medio: MedioPago) -> RespuestaPasarela:
        if self.caida:
            raise DependenciaNoDisponible("La pasarela de pagos no respondió", proveedor="pasarela")
        with self._cerrojo:
            referencia = f"TX-{next(self._consecutivo)}"
            estado = self.resultados[medio]
            self._transacciones[referencia] = estado
            self._por_pago[id_pago] = referencia
            self.cobros.append((id_pago, monto, medio))
        return RespuestaPasarela(estado, referencia)

    def consultar_pago(self, id_pago: str) -> RespuestaPasarela | None:
        if self.caida:
            raise DependenciaNoDisponible("La pasarela de pagos no respondió", proveedor="pasarela")
        with self._cerrojo:
            referencia = self._por_pago.get(id_pago)
            if referencia is None:
                return None
            return RespuestaPasarela(self._transacciones[referencia], referencia)

    def consultar(self, referencia: str) -> RespuestaPasarela:
        if self.caida:
            raise DependenciaNoDisponible("La pasarela de pagos no respondió", proveedor="pasarela")
        with self._cerrojo:
            estado = self._transacciones.get(referencia, EstadoPago.PENDIENTE)
            if estado is EstadoPago.PENDIENTE:
                # Simulación: el banco confirma en cuanto se le pregunta.
                estado = self.pendiente_se_resuelve_en
                self._transacciones[referencia] = estado
        return RespuestaPasarela(estado, referencia)
