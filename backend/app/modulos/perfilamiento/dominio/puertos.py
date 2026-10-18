"""Puertos del módulo de perfilamiento.

    ProveedorOpenFinance → el proveedor externo, detrás del adaptador de ARQ-02. Simulado hoy
    FuenteDePerfiles     → el adaptador resiliente, tal como lo ve el caso de uso
    CacheDePerfiles      → copia local del último perfil; se vacía por evento al revocar

El núcleo solo conoce estas interfaces. Ninguna referencia a un proveedor concreto sale
de `infraestructura/` (ARQ-02, criterio 1).
"""

from __future__ import annotations

from typing import Protocol

from .modelo import PerfilDeRiesgo, RespuestaPerfil


class ProveedorOpenFinance(Protocol):
    def perfil_de(self, id_cliente: str, id_consentimiento: str) -> PerfilDeRiesgo:
        """Consulta el perfil bajo un consentimiento vigente.

        Si el proveedor falla, lanza `ProveedorOpenFinanceNoDisponibleError`. El tiempo
        límite no lo controla el proveedor sino el adaptador que lo envuelve.
        """
        raise NotImplementedError

    def sondear(self) -> None:
        """Llamada barata para saber si el proveedor volvió. Lanza si sigue caído."""
        raise NotImplementedError


class CacheDePerfiles(Protocol):
    def guardar(self, id_cliente: str, perfil: object) -> None:
        raise NotImplementedError

    def obtener(self, id_cliente: str) -> object | None:
        raise NotImplementedError

    def invalidar(self, id_cliente: str) -> bool:
        raise NotImplementedError


class FuenteDePerfiles(Protocol):
    """Lo que el caso de uso necesita del adaptador resiliente: perfil y métricas.

    El caso de uso depende de esto y no de `AdaptadorOpenFinanceResiliente`, así un cambio
    en el adaptador o en el proveedor se queda en `infraestructura/` (ARQ-02, criterio 3).
    """

    def perfil_de(self, id_cliente: str, id_consentimiento: str) -> RespuestaPerfil:
        raise NotImplementedError

    def resumen(self) -> dict[str, object]:
        raise NotImplementedError
