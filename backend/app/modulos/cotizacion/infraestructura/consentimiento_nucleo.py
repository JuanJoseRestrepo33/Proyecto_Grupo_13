"""Consulta de consentimiento sobre el módulo real (WEB-F02).

Implementa el puerto `ConsultaConsentimiento` de cotización preguntándole al módulo de
consentimiento por su interfaz pública. No hay caché en medio, a propósito: la revocación
tiene que verse en la siguiente cotización (RC-04).
"""

from __future__ import annotations

from app.modulos.consentimiento import ServicioDeConsentimiento
from app.modulos.cotizacion.dominio.puertos import EstadoConsentimiento


class ConsentimientoDelNucleo:
    def __init__(self, consentimiento: ServicioDeConsentimiento) -> None:
        self._consentimiento = consentimiento

    def consentimiento_vigente(self, id_cliente: str, finalidad: str) -> EstadoConsentimiento:
        estado = self._consentimiento.consentimiento_vigente(id_cliente, finalidad)
        return EstadoConsentimiento(
            vigente=estado.vigente, id_consentimiento=estado.id_consentimiento
        )
