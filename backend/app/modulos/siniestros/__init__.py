"""Reporte, seguimiento e indemnización de siniestros (WEB-F05)."""

from app.compartido.modulo import DescriptorDeModulo

from .api.rutas import router

MODULO = DescriptorDeModulo(
    nombre="siniestros",
    descripcion="Reporte, seguimiento e indemnización de siniestros",
    historia="WEB-F05",
    router=router,
)
