"""Registro, verificación de identidad (KYC/AML) y autenticación del cliente (MOB-F01).

Interfaz pública del módulo. Lo que hay hoy es el hook de KYC/AML: solicitar y consultar
la verificación y el estado de identidad de la cuenta. Otros módulos preguntan por
`obtener_servicio().estado_identidad(id_cliente)` antes de dejar entrar a una función que
exige identidad verificada.
"""

from app.compartido.modulo import DescriptorDeModulo

from .api.dependencias import estado_dependencias, obtener_servicio
from .api.rutas import router
from .aplicacion.servicio import ServicioDeVerificacionDeIdentidad
from .dominio.modelo import EstadoIdentidad, EstadoVerificacion, MotivoRechazo

MODULO = DescriptorDeModulo(
    nombre="identidad",
    descripcion="Registro, verificación de identidad (KYC/AML) y autenticación del cliente",
    historia="MOB-F01",
    router=router,
    dependencias=estado_dependencias,
)

__all__ = [
    "MODULO",
    "EstadoIdentidad",
    "EstadoVerificacion",
    "MotivoRechazo",
    "ServicioDeVerificacionDeIdentidad",
    "obtener_servicio",
]
