"""Distribución embebida y back-office de socios (API-F01)."""

from app.compartido.modulo import DescriptorDeModulo

from .api.rutas import router

MODULO = DescriptorDeModulo(
    nombre="socios",
    descripcion="Distribución embebida y back-office de socios",
    historia="API-F01",
    router=router,
)
