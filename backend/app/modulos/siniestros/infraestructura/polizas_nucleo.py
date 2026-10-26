"""Consulta de pólizas sobre el módulo real, por su interfaz pública (ARQ-01)."""

from __future__ import annotations

from app.modulos.polizas import ServicioDePolizas

from ..dominio.puertos import VigenciaDePoliza


class PolizasDelNucleo:
    def __init__(self, polizas: ServicioDePolizas) -> None:
        self._polizas = polizas

    def poliza_del_cliente(self, numero: str, id_cliente: str) -> VigenciaDePoliza | None:
        poliza = self._polizas.poliza_del_cliente(numero, id_cliente)
        if poliza is None:
            return None
        return VigenciaDePoliza(poliza.numero, poliza.producto, poliza.inicio, poliza.fin)
