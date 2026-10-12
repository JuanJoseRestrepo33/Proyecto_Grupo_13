"""Puertos del módulo de cotización.

Lo que cotización necesita de afuera, expresado como contratos y no como dependencias.
Quien los implementa queda del otro lado de la frontera:

    ConsultaConsentimiento → módulo consentimiento (WEB-F02, Sergio)
    ConsultaPerfilDeRiesgo → módulo perfilamiento, vía el adaptador de Open Finance
                             con circuit breaker y caché (ARQ-02, Juan)
    RegistroDeDecisiones   → log de auditoría append-only (ARQ-03)
    RepositorioCotizaciones → persistencia del propio módulo

Mientras esos módulos no existan, el arranque inyecta implementaciones simuladas de
`infraestructura/`. Cambiarlas por las reales no toca ni el dominio ni la aplicación:
eso es lo que sostiene RC-05.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from typing import Protocol

from .modelo import Cotizacion, PerfilDeRiesgo


@dataclass(frozen=True, slots=True)
class EstadoConsentimiento:
    vigente: bool
    id_consentimiento: str | None = None


class ConsultaConsentimiento(Protocol):
    """Puerto hacia el módulo de consentimiento (WEB-F02)."""

    def consentimiento_vigente(self, id_cliente: str, finalidad: str) -> EstadoConsentimiento:
        """Si el cliente autorizó el uso de sus datos financieros para esa finalidad."""
        raise NotImplementedError


@dataclass(frozen=True, slots=True)
class RespuestaPerfil:
    """Perfil obtenido, y si vino degradado.

    `degradado` es True cuando el proveedor de Open Finance no respondió a tiempo y el
    perfil salió de la caché. La oferta sigue siendo válida: es la táctica de
    disponibilidad de RC-03.
    """

    perfil: PerfilDeRiesgo | None
    degradado: bool = False


class ConsultaPerfilDeRiesgo(Protocol):
    """Puerto hacia perfilamiento. La implementación real lleva circuit breaker."""

    def perfil_de(self, id_cliente: str, id_consentimiento: str) -> RespuestaPerfil:
        pass


class RegistroDeDecisiones(Protocol):
    """Puerto hacia el log de auditoría append-only (ARQ-03, RC-08).

    Solo escribe. No existe forma de modificar ni borrar una decisión registrada.
    """

    def registrar(
        self,
        *,
        id_cotizacion: str,
        id_consentimiento: str | None,
        version_regla: str,
        variables_entrada: dict[str, object],
        resultado: dict[str, object],
        ocurrido_en: datetime,
    ) -> str:
        raise NotImplementedError


class RepositorioCotizaciones(Protocol):
    def guardar(self, cotizacion: Cotizacion) -> None:
        pass

    def obtener(self, id_cotizacion: str) -> Cotizacion | None:
        pass

    def siguiente_id(self, anio: int) -> str:
        """Identificador legible por humanos, con el formato QT-AAAA-NNNNN."""
        raise NotImplementedError
