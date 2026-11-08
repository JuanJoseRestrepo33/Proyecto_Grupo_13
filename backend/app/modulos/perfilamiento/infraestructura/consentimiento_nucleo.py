"""Verificación del consentimiento sobre el módulo real, por su interfaz pública (ARQ-05)."""

from __future__ import annotations

from app.modulos.consentimiento import ServicioDeConsentimiento


class ConsentimientoDelNucleo:
    def __init__(self, consentimientos: ServicioDeConsentimiento) -> None:
        self._consentimientos = consentimientos

    def vigente(self, id_cliente: str, id_consentimiento: str) -> bool:
        return self._consentimientos.esta_vigente(id_cliente, id_consentimiento)
