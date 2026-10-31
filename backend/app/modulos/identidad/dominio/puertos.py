"""Puertos del módulo de identidad.

    ProveedorKYC               → adaptador del proveedor de KYC/AML (ARQ-02). Simulado hoy
    RepositorioVerificaciones  → persistencia del propio módulo
    RegistroDeAuditoria        → módulo de auditoría (ARQ-03), por su interfaz pública
    EmisorDeTokens             → firma y validación de los tokens de acceso (ES256, WEB-F13)
    RepositorioCuentas, RepositorioRegistros, RepositorioRefrescos → persistencia propia
    CanalDeCodigos             → envío del código de verificación al correo. Simulado hoy
    RegistroDeAccesos          → registros, verificaciones de correo e ingresos, en auditoría

El núcleo solo conoce `ProveedorKYC`; ninguna referencia a un proveedor concreto sale de
`infraestructura/` (ARQ-02, criterio 1).
"""

from __future__ import annotations

from datetime import datetime, timedelta
from typing import Protocol

from .cuenta import Cuenta, Registro
from .modelo import DatosDeIdentidad, ResultadoKYC, VerificacionIdentidad
from .sesion import ReclamosDeAcceso, Refresco, TokenEmitido


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

    def quitar(self, id_verificacion: str) -> None:
        """Borra una reserva cuyo intento no llegó al proveedor."""
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


class RepositorioCuentas(Protocol):
    def guardar(self, cuenta: Cuenta) -> None:
        raise NotImplementedError

    def por_id(self, id_cliente: str) -> Cuenta | None:
        raise NotImplementedError

    def por_correo(self, correo: str) -> Cuenta | None:
        """`correo` ya normalizado."""
        raise NotImplementedError

    def por_documento(self, numero_documento: str) -> Cuenta | None:
        raise NotImplementedError

    def siguiente_id(self, anio: int) -> str:
        """Identificador legible, CLI-AAAA-N con al menos 6 dígitos, que nunca se repite.

        Un token de acceso vigente sobrevive a un despliegue: si el id se reutilizara, ese
        token abriría la cuenta de otra persona.
        """
        raise NotImplementedError


class RepositorioRegistros(Protocol):
    def guardar(self, registro: Registro) -> None:
        raise NotImplementedError

    def obtener(self, id_registro: str) -> Registro | None:
        raise NotImplementedError

    def siguiente_id(self, anio: int) -> str:
        """Identificador legible, con el formato REG-AAAA-NNNNNN."""
        raise NotImplementedError


class RepositorioRefrescos(Protocol):
    def guardar(self, refresco: Refresco) -> None:
        raise NotImplementedError

    def tomar(self, resumen: str) -> Refresco | None:
        """Devuelve el refresco y lo borra en la misma operación: es de un solo uso."""
        raise NotImplementedError


class CanalDeCodigos(Protocol):
    def nuevo_codigo(self) -> str:
        """Código de 6 dígitos."""
        raise NotImplementedError

    def enviar(self, correo: str, codigo: str) -> None:
        raise NotImplementedError


class RegistroDeAccesos(Protocol):
    def registrar(
        self,
        suceso: str,
        *,
        id_cliente: str | None,
        canal: str,
        ocurrido_en: datetime,
        resultado: dict[str, object],
    ) -> str:
        """Deja el suceso en el log. Nunca recibe contraseñas, códigos ni tokens."""
        raise NotImplementedError
