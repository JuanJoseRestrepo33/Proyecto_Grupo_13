"""Reporte, seguimiento e indemnización de siniestros (WEB-F05).

Interfaz pública del módulo. El seguimiento (WEB-F06) y las notificaciones (MOB-F06) se
construyen sobre `obtener_servicio()` y sobre el evento de siniestro reportado.
"""

from app.compartido.modulo import DescriptorDeModulo

from .api.dependencias import estado_dependencias, obtener_servicio
from .api.rutas import router
from .aplicacion.servicio import ServicioDeSiniestros

MODULO = DescriptorDeModulo(
    nombre="siniestros",
    descripcion="Reporte, seguimiento e indemnización de siniestros",
    historia="WEB-F05",
    router=router,
    dependencias=estado_dependencias,
)

__all__ = ["MODULO", "ServicioDeSiniestros", "obtener_servicio"]
