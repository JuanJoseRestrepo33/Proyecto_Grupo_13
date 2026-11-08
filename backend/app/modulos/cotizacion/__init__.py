"""Capacidad de cotización: catálogo, tarificación y oferta (WEB-F01).

Interfaz pública del módulo. Suscripción (WEB-F03) lee aquí la oferta que el cliente acepta,
con `obtener_servicio().obtener(id_cotizacion)`, y comprueba su vigencia con el estado de la
cotización; nunca importa nada más profundo (ARQ-01).
"""

from app.compartido.modulo import DescriptorDeModulo

from .api.dependencias import estado_dependencias, obtener_servicio
from .api.rutas import router
from .aplicacion.servicio import ServicioDeCotizacion
from .dominio.modelo import Cotizacion, EstadoCotizacion, OfertaVencidaError

MODULO = DescriptorDeModulo(
    nombre="cotizacion",
    descripcion="Catálogo de productos, tarificación y oferta personalizada con vigencia",
    historia="WEB-F01",
    router=router,
    dependencias=estado_dependencias,
)

__all__ = [
    "MODULO",
    "Cotizacion",
    "EstadoCotizacion",
    "OfertaVencidaError",
    "ServicioDeCotizacion",
    "obtener_servicio",
]
