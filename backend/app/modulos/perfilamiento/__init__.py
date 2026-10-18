"""Perfil de riesgo del cliente a partir de sus datos de Open Finance (WEB-F01).

Interfaz pública. Hoy expone la caché de perfiles, que se vacía sola cuando el cliente
revoca su consentimiento (WEB-F02, criterio 5). El adaptador de Open Finance llega con
ARQ-02.
"""

from app.compartido.modulo import DescriptorDeModulo

from .api.dependencias import estado_dependencias, iniciar, obtener_cache
from .api.rutas import router

MODULO = DescriptorDeModulo(
    nombre="perfilamiento",
    descripcion="Perfil de riesgo del cliente a partir de sus datos de Open Finance",
    historia="WEB-F01",
    router=router,
    dependencias=estado_dependencias,
    iniciar=iniciar,
)

__all__ = ["MODULO", "obtener_cache"]
