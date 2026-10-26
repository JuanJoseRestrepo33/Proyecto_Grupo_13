"""Consulta y ciclo de vida de pólizas: renovación, cancelación y modificación (WEB-F04).

Interfaz pública del módulo. El reporte de siniestros (WEB-F05) valida aquí que la póliza
sea del cliente y cubra la fecha del evento, con `obtener_servicio().poliza_del_cliente(...)`.
"""

from app.compartido.modulo import DescriptorDeModulo

from .api.dependencias import estado_dependencias, obtener_servicio
from .api.rutas import router
from .aplicacion.servicio import ServicioDePolizas
from .dominio.modelo import EstadoPoliza, Poliza

MODULO = DescriptorDeModulo(
    nombre="polizas",
    descripcion="Consulta y ciclo de vida de pólizas: renovación, cancelación y modificación",
    historia="WEB-F04",
    router=router,
    dependencias=estado_dependencias,
)

__all__ = [
    "MODULO",
    "EstadoPoliza",
    "Poliza",
    "ServicioDePolizas",
    "obtener_servicio",
]
