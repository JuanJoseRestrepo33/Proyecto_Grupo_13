"""Otorgamiento, consulta y revocación del consentimiento Open Finance (WEB-F02)."""

from app.compartido.modulo import DescriptorDeModulo

from .api.rutas import router

MODULO = DescriptorDeModulo(
    nombre="consentimiento",
    descripcion="Otorgamiento, consulta y revocación del consentimiento Open Finance",
    historia="WEB-F02",
    router=router,
)
