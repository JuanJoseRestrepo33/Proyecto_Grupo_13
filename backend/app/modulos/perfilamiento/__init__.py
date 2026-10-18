"""Perfil de riesgo del cliente a partir de sus datos de Open Finance (WEB-F01, ARQ-02).

Interfaz pública. Cotización y suscripción obtienen el perfil por
`obtener_servicio().perfil_de(id_cliente, id_consentimiento)`, siempre después de
verificar el consentimiento. El adaptador de Open Finance lleva presupuesto, circuit
breaker y caché; la caché se vacía sola cuando el cliente revoca (WEB-F02, criterio 5).
"""

from app.compartido.modulo import DescriptorDeModulo

from .api.dependencias import estado_dependencias, iniciar, obtener_cache, obtener_servicio
from .api.rutas import router
from .aplicacion.servicio import ServicioDePerfilamiento
from .dominio.modelo import Origen, PerfilDeRiesgo, RespuestaPerfil

MODULO = DescriptorDeModulo(
    nombre="perfilamiento",
    descripcion="Perfil de riesgo del cliente a partir de sus datos de Open Finance",
    historia="WEB-F01",
    router=router,
    dependencias=estado_dependencias,
    iniciar=iniciar,
)

__all__ = [
    "MODULO",
    "Origen",
    "PerfilDeRiesgo",
    "RespuestaPerfil",
    "ServicioDePerfilamiento",
    "obtener_cache",
    "obtener_servicio",
]
