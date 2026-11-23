"""Decisión de suscripción, firma y emisión de la póliza (WEB-F03).

Interfaz pública del módulo. Orquesta el checkout sobre cotización, pagos y pólizas, cada uno
por su interfaz pública.
"""

from app.compartido.modulo import DescriptorDeModulo

from .api.dependencias import estado_dependencias, obtener_servicio
from .api.rutas import router
from .aplicacion.servicio import ServicioDeSuscripcion

MODULO = DescriptorDeModulo(
    nombre="suscripcion",
    descripcion="Decisión de suscripción, firma y emisión de la póliza",
    historia="WEB-F03",
    router=router,
    dependencias=estado_dependencias,
)

__all__ = ["MODULO", "ServicioDeSuscripcion", "obtener_servicio"]
