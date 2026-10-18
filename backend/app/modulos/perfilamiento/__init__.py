"""Perfil de riesgo del cliente a partir de sus datos de Open Finance (WEB-F01)."""

from app.compartido.modulo import DescriptorDeModulo

from .api.rutas import router

MODULO = DescriptorDeModulo(
    nombre="perfilamiento",
    descripcion="Perfil de riesgo del cliente a partir de sus datos de Open Finance",
    historia="WEB-F01",
    router=router,
)
