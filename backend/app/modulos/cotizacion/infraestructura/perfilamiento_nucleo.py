"""Consulta del perfil de riesgo sobre el módulo real de perfilamiento (ARQ-02).

Implementa el puerto `ConsultaPerfilDeRiesgo` de cotización traduciendo la respuesta de
perfilamiento al modelo de cotización. Solo importa la interfaz pública de ese módulo.
"""

from __future__ import annotations

from app.modulos.cotizacion.dominio.modelo import PerfilDeRiesgo
from app.modulos.cotizacion.dominio.puertos import RespuestaPerfil
from app.modulos.perfilamiento import Origen, ServicioDePerfilamiento


class PerfilamientoDelNucleo:
    def __init__(self, perfilamiento: ServicioDePerfilamiento) -> None:
        self._perfilamiento = perfilamiento

    def perfil_de(self, id_cliente: str, id_consentimiento: str) -> RespuestaPerfil:
        respuesta = self._perfilamiento.perfil_de(id_cliente, id_consentimiento)
        if respuesta.perfil is None:
            return RespuestaPerfil(perfil=None, degradado=True)
        perfil = PerfilDeRiesgo(
            puntaje=respuesta.perfil.puntaje,
            comportamiento_pago=respuesta.perfil.comportamiento_pago,
            nivel_endeudamiento=respuesta.perfil.nivel_endeudamiento,
            desde_cache=respuesta.origen is Origen.CACHE,
        )
        return RespuestaPerfil(perfil=perfil, degradado=respuesta.degradado)
