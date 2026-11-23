"""Log de auditoría append-only con cadena de hash (ARQ-03).

Interfaz pública del módulo. Los demás módulos registran aquí sus decisiones y eventos
de consentimiento a través de `obtener_servicio()`; nunca importan nada más profundo.
"""

from app.compartido.modulo import DescriptorDeModulo

from .api.dependencias import (
    estado_dependencias,
    obtener_servicio,
    vaciar_escrituras_pendientes,
)
from .api.rutas import router
from .aplicacion.servicio import ServicioDeAuditoria
from .dominio.modelo import EventoAuditable, InformeDeIntegridad, Registro

MODULO = DescriptorDeModulo(
    nombre="auditoria",
    descripcion="Registro inmutable y verificable de decisiones y eventos de consentimiento",
    historia="ARQ-03",
    router=router,
    dependencias=estado_dependencias,
    detener=vaciar_escrituras_pendientes,
)

__all__ = [
    "MODULO",
    "EventoAuditable",
    "InformeDeIntegridad",
    "Registro",
    "ServicioDeAuditoria",
    "obtener_servicio",
    "vaciar_escrituras_pendientes",
]
