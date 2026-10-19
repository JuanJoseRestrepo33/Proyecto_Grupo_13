"""Capacidad de cotización: catálogo, tarificación y oferta (WEB-F01)."""

from app.compartido.modulo import DescriptorDeModulo

from .api.dependencias import estado_dependencias
from .api.rutas import router

MODULO = DescriptorDeModulo(
    nombre="cotizacion",
    descripcion="Catálogo de productos, tarificación y oferta personalizada con vigencia",
    historia="WEB-F01",
    router=router,
    dependencias=estado_dependencias,
)
