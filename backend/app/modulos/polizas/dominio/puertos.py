"""Puertos del módulo de pólizas.

RepositorioPolizas → persistencia del propio módulo. En memoria hasta WEB-F03, que emite
                     pólizas de verdad
"""

from __future__ import annotations

from typing import Protocol

from .modelo import Poliza


class RepositorioPolizas(Protocol):
    def guardar(self, poliza: Poliza) -> None:
        raise NotImplementedError

    def por_numero(self, numero: str) -> Poliza | None:
        raise NotImplementedError

    def del_cliente(self, id_cliente: str) -> tuple[Poliza, ...]:
        raise NotImplementedError
