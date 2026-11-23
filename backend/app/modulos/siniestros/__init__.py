"""Reporte, seguimiento e indemnización de siniestros (WEB-F05).

Interfaz pública del módulo. Las notificaciones (MOB-F06) se suscriben a
`EVENTO_SINIESTRO_ESTADO_CAMBIADO`, que se publica en cada cambio de estado (WEB-F06).
"""

from app.compartido.modulo import DescriptorDeModulo

from .api.dependencias import estado_dependencias, obtener_servicio
from .api.rutas import router
from .aplicacion.servicio import (
    EVENTO_SINIESTRO_ESTADO_CAMBIADO,
    EVENTO_SINIESTRO_REPORTADO,
    ServicioDeSiniestros,
)

MODULO = DescriptorDeModulo(
    nombre="siniestros",
    descripcion="Reporte, seguimiento e indemnización de siniestros",
    historia="WEB-F05",
    router=router,
    dependencias=estado_dependencias,
)

__all__ = [
    "EVENTO_SINIESTRO_ESTADO_CAMBIADO",
    "EVENTO_SINIESTRO_REPORTADO",
    "MODULO",
    "ServicioDeSiniestros",
    "obtener_servicio",
]
