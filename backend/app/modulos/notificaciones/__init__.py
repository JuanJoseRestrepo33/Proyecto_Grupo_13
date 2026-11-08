"""Notificaciones push a los dispositivos del cliente (MOB-F06).

El módulo se suscribe a `siniestro-estado-cambiado` al arrancar: cada cambio de estado de un
siniestro (WEB-F06) le llega al cliente como push en sus dispositivos registrados.
"""

from app.compartido.modulo import DescriptorDeModulo

from .api.dependencias import estado_dependencias, iniciar, obtener_servicio
from .api.rutas import router
from .aplicacion.servicio import ServicioDeNotificaciones

MODULO = DescriptorDeModulo(
    nombre="notificaciones",
    descripcion="Notificaciones push a los dispositivos del cliente",
    historia="MOB-F06",
    router=router,
    dependencias=estado_dependencias,
    iniciar=iniciar,
)

__all__ = ["MODULO", "ServicioDeNotificaciones", "obtener_servicio"]
