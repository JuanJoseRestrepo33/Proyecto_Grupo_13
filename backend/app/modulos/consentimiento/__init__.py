"""Otorgamiento, consulta y revocación del consentimiento Open Finance (WEB-F02).

Interfaz pública del módulo. Cotización y suscripción preguntan aquí si un cliente
autorizó una finalidad, a través de `obtener_servicio().consentimiento_vigente(...)`.
"""

from app.compartido.modulo import DescriptorDeModulo

from .api.dependencias import estado_dependencias, obtener_servicio
from .api.rutas import router
from .aplicacion.servicio import (
    EVENTO_OTORGADO,
    EVENTO_RENOVADO,
    EVENTO_REVOCADO,
    EVENTO_VENCIDO,
    ServicioDeConsentimiento,
)
from .dominio.catalogo import CodigoFinalidad
from .dominio.modelo import EstadoDeConsentimiento

MODULO = DescriptorDeModulo(
    nombre="consentimiento",
    descripcion="Otorgamiento, consulta y revocación del consentimiento Open Finance",
    historia="WEB-F02",
    router=router,
    dependencias=estado_dependencias,
)

__all__ = [
    "EVENTO_OTORGADO",
    "EVENTO_RENOVADO",
    "EVENTO_REVOCADO",
    "EVENTO_VENCIDO",
    "MODULO",
    "CodigoFinalidad",
    "EstadoDeConsentimiento",
    "ServicioDeConsentimiento",
    "obtener_servicio",
]
