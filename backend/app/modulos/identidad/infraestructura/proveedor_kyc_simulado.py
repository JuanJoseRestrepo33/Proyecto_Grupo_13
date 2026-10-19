"""Proveedor de KYC/AML simulado (ARQ-02, criterio 2: determinista, apto para aserciones).

Sigue la misma regla que el mock del BFF móvil, para que la app se comporte igual contra
el núcleo que contra sus mocks: el resultado depende del último dígito del documento.

    7 → rechazada (datos_no_coinciden, con reintento)
    8 → en_revision (revisión manual)
    9 → en_proceso; la siguiente consulta la encuentra aprobada
    cualquier otro → aprobada

`caido=True` imita al proveedor fuera de servicio: toda llamada falla con el error tipado
del dominio, que es lo único que el núcleo debe ver (ARQ-02, criterio 4).
"""

from __future__ import annotations

import threading

from ..dominio.errores import ProveedorKYCNoDisponibleError
from ..dominio.modelo import DatosDeIdentidad, EstadoVerificacion, MotivoRechazo, ResultadoKYC

NOMBRE = "kyc-simulado"


class ProveedorKYCSimulado:
    def __init__(self, caido: bool = False) -> None:
        self.caido = caido
        self._pendientes: dict[str, int] = {}
        self._contador = 0
        self._cerrojo = threading.Lock()

    def _referencia(self) -> str:
        with self._cerrojo:
            self._contador += 1
            return f"SIM-{self._contador:06d}"

    def _si_caido(self) -> None:
        if self.caido:
            raise ProveedorKYCNoDisponibleError(
                "El proveedor de KYC/AML no responde", proveedor=NOMBRE
            )

    def verificar(self, datos: DatosDeIdentidad) -> ResultadoKYC:
        self._si_caido()
        referencia = self._referencia()
        ultimo = datos.numero_documento[-1]
        if ultimo == "7":
            return ResultadoKYC(
                EstadoVerificacion.RECHAZADA,
                NOMBRE,
                referencia,
                motivo=MotivoRechazo.DATOS_NO_COINCIDEN,
                reintento_permitido=True,
            )
        if ultimo == "8":
            return ResultadoKYC(EstadoVerificacion.EN_REVISION, NOMBRE, referencia)
        if ultimo == "9":
            with self._cerrojo:
                self._pendientes[referencia] = 1
            return ResultadoKYC(EstadoVerificacion.EN_PROCESO, NOMBRE, referencia)
        return ResultadoKYC(EstadoVerificacion.APROBADA, NOMBRE, referencia)

    def consultar(self, referencia_proveedor: str) -> ResultadoKYC:
        self._si_caido()
        with self._cerrojo:
            restantes = self._pendientes.get(referencia_proveedor, 0)
            if restantes > 0:
                self._pendientes[referencia_proveedor] = restantes - 1
        # La primera consulta después de solicitar ya la encuentra aprobada, como el mock.
        return ResultadoKYC(EstadoVerificacion.APROBADA, NOMBRE, referencia_proveedor)
