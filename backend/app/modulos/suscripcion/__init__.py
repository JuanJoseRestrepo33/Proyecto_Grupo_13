"""Decisión de suscripción, firma y emisión de la póliza (WEB-F03)."""

from app.compartido.modulo import DescriptorDeModulo

from .api.rutas import router

MODULO = DescriptorDeModulo(
    nombre="suscripcion",
    descripcion="Decisión de suscripción, firma y emisión de la póliza",
    historia="WEB-F03",
    router=router,
)
