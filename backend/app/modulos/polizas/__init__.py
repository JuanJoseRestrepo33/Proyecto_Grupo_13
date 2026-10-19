"""Consulta y ciclo de vida de pólizas: renovación, cancelación y modificación (WEB-F04)."""

from app.compartido.modulo import DescriptorDeModulo

from .api.rutas import router

MODULO = DescriptorDeModulo(
    nombre="polizas",
    descripcion="Consulta y ciclo de vida de pólizas: renovación, cancelación y modificación",
    historia="WEB-F04",
    router=router,
)
