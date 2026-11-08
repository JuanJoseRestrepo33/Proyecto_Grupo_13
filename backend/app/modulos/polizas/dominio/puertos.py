"""Puertos del módulo de pólizas.

RepositorioPolizas   → persistencia del propio módulo
AlmacenDeDocumentos  → documento PDF de la póliza (memoria o Cloud Storage)
GeneradorDeDocumento → arma el PDF a partir de la póliza
RegistroDeAuditoria  → log de auditoría (ARQ-03), por su interfaz pública
"""

from __future__ import annotations

from typing import Protocol

from .modelo import Poliza


class RepositorioPolizas(Protocol):
    def guardar(self, poliza: Poliza) -> None:
        """Inserta o reemplaza la póliza por su número."""
        raise NotImplementedError

    def emitir_si_nueva(self, poliza: Poliza) -> Poliza:
        """Guarda la póliza salvo que la suscripción ya tenga una; entonces devuelve esa sin
        guardar nada. Es atómico: una suscripción nunca queda con dos pólizas."""
        raise NotImplementedError

    def por_numero(self, numero: str) -> Poliza | None:
        raise NotImplementedError

    def por_suscripcion(self, id_suscripcion: str) -> Poliza | None:
        raise NotImplementedError

    def del_cliente(self, id_cliente: str) -> tuple[Poliza, ...]:
        raise NotImplementedError

    def siguiente_numero(self, anio: int) -> str:
        """Número que ve el cliente, con el formato POL-AAAA-NNNNNN."""
        raise NotImplementedError


class AlmacenDeDocumentos(Protocol):
    def guardar(self, numero_poliza: str, id_suscripcion: str, contenido: bytes) -> str:
        """Guarda el PDF y devuelve su identificador en el almacén. No sobrescribe."""
        raise NotImplementedError

    def leer(self, id_documento: str) -> bytes | None:
        raise NotImplementedError

    def borrar(self, id_documento: str) -> None:
        """Borra un documento que no quedó ligado a ninguna póliza. No falla si no existe."""
        raise NotImplementedError


class GeneradorDeDocumento(Protocol):
    def generar(self, poliza: Poliza) -> bytes:
        raise NotImplementedError


class RegistroDeAuditoria(Protocol):
    def registrar_emision(self, poliza: Poliza) -> str:
        raise NotImplementedError
