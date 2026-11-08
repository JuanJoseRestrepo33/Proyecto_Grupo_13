"""Cobro de primas y pago de indemnizaciones a través de la pasarela (WEB-F03).

Interfaz pública del módulo. Suscripción cobra la prima con `obtener_servicio().cobrar(...)`
y consulta el resultado con `consultar(...)`; nunca importa nada más profundo (ARQ-01).
"""

from app.compartido.modulo import DescriptorDeModulo

from .api.dependencias import estado_dependencias, obtener_servicio
from .api.esquemas import PagoDto, a_pago_dto
from .api.rutas import router
from .aplicacion.servicio import ServicioDePagos
from .dominio.modelo import EstadoPago, MedioPago, Pago

MODULO = DescriptorDeModulo(
    nombre="pagos",
    descripcion="Cobro de primas y pago de indemnizaciones a través de la pasarela",
    historia="WEB-F03",
    router=router,
    dependencias=estado_dependencias,
)

__all__ = [
    "MODULO",
    "EstadoPago",
    "MedioPago",
    "Pago",
    "PagoDto",
    "ServicioDePagos",
    "a_pago_dto",
    "obtener_servicio",
]
