"""Cobro de primas y pago de indemnizaciones a través de la pasarela (WEB-F03)."""

from app.compartido.modulo import DescriptorDeModulo

from .api.rutas import router

MODULO = DescriptorDeModulo(
    nombre="pagos",
    descripcion="Cobro de primas y pago de indemnizaciones a través de la pasarela",
    historia="WEB-F03",
    router=router,
)
