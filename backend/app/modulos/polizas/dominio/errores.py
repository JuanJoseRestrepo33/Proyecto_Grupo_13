"""Errores del módulo de pólizas."""

from __future__ import annotations

from app.compartido.errores import DependenciaNoDisponible, RecursoNoEncontrado


class PolizaNoEncontradaError(RecursoNoEncontrado):
    """No existe o no es del cliente: los dos casos responden igual."""

    codigo = "poliza_no_encontrada"


class DocumentoNoDisponibleError(RecursoNoEncontrado):
    """La póliza no tiene documento (las de semilla) o el almacén ya no lo tiene."""

    codigo = "documento_no_disponible"


class AlmacenDeDocumentosNoDisponibleError(DependenciaNoDisponible):
    codigo = "almacenamiento_no_disponible"
