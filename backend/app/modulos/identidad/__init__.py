"""Registro, verificación de identidad (KYC/AML) y autenticación del cliente (MOB-F01)."""

from app.compartido.modulo import DescriptorDeModulo

from .api.rutas import router

MODULO = DescriptorDeModulo(
    nombre="identidad",
    descripcion="Registro, verificación de identidad (KYC/AML) y autenticación del cliente",
    historia="MOB-F01",
    router=router,
)
