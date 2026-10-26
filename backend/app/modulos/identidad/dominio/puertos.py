"""Puertos del módulo de identidad.

    ProveedorKYC               → adaptador del proveedor de KYC/AML (ARQ-02). Simulado hoy
    RepositorioVerificaciones  → persistencia del propio módulo
    RegistroDeAuditoria        → módulo de auditoría (ARQ-03), por su interfaz pública
    EmisorDeTokens             → firma y validación de los tokens de acceso (ES256, WEB-F13)

El núcleo solo conoce `ProveedorKYC`; ninguna referencia a un proveedor concreto sale de
`infraestructura/` (ARQ-02, criterio 1).
"""

from __future__ import annotations

from datetime import datetime, timedelta
from typing import Protocol

from .modelo import DatosDeIdentidad, ResultadoKYC, VerificacionIdentidad
from .sesion import ReclamosDeAcceso, TokenEmitido


class ProveedorKYC(Protocol):
    def verificar(self, datos: DatosDeIdentidad) -> ResultadoKYC:
        """Envía los datos y devuelve el resultado, que puede ser `en_proceso`.

        Si el proveedor falla o excede su tiempo límite, lanza
        `ProveedorKYCNoDisponibleError`, nunca una excepción del cliente HTTP.
        """
        raise NotImplementedError

    def consultar(self, referencia_proveedor: str) -> ResultadoKYC:
        """Estado actual de una verificación que quedó `en_proceso`."""
        raise NotImplementedError


class RepositorioVerificaciones(Protocol):
    def guardar(self, verificacion: VerificacionIdentidad) -> None:
        raise NotImplementedError

    def ultima_del_cliente(self, id_cliente: str) -> VerificacionIdentidad | None:
        raise NotImplementedError


class RegistroDeAuditoria(Protocol):
    def registrar(self, verificacion: VerificacionIdentidad, suceso: str) -> str:
        """Deja el intento en el log con resultado, proveedor, canal y marca de tiempo."""
        raise NotImplementedError


class EmisorDeTokens(Protocol):
    def emitir_acceso(
        self, id_cliente: str, canal: str, ahora: datetime, vigencia: timedelta
    ) -> TokenEmitido:
        raise NotImplementedError

    def validar_acceso(self, token: str, ahora: datetime) -> ReclamosDeAcceso:
        """Los reclamos de un token vigente; si no lo es, `SesionNoVigenteError`."""
        raise NotImplementedError
