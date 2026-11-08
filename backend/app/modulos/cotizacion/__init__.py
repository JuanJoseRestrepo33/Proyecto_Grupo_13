"""Capacidad de cotización: catálogo, tarificación y oferta (WEB-F01).

Interfaz pública del módulo. Suscripción (WEB-F03) lee aquí la oferta que el cliente acepta,
con `obtener_servicio().obtener(id_cotizacion)`, y comprueba su vigencia con el estado de la
cotización; nunca importa nada más profundo (ARQ-01). Pólizas (WEB-F04) toma del catálogo las
condiciones de las pólizas de prueba, que no vienen de una oferta, con `producto_del_catalogo`.
"""

from app.compartido.modulo import DescriptorDeModulo

from .api.dependencias import estado_dependencias, obtener_servicio
from .api.rutas import router
from .aplicacion.servicio import ServicioDeCotizacion
from .dominio.catalogo import Producto
from .dominio.catalogo import obtener as _obtener_producto
from .dominio.modelo import Cotizacion, EstadoCotizacion, ProductoId

MODULO = DescriptorDeModulo(
    nombre="cotizacion",
    descripcion="Catálogo de productos, tarificación y oferta personalizada con vigencia",
    historia="WEB-F01",
    router=router,
    dependencias=estado_dependencias,
)


def producto_del_catalogo(producto: str) -> Producto | None:
    """El producto del catálogo, o None si el código no es de un producto."""
    try:
        return _obtener_producto(ProductoId(producto))
    except ValueError:
        return None


__all__ = [
    "MODULO",
    "Cotizacion",
    "EstadoCotizacion",
    "Producto",
    "ServicioDeCotizacion",
    "obtener_servicio",
    "producto_del_catalogo",
]
