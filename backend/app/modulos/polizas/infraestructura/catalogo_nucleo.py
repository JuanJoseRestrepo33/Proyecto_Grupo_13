"""Catálogo de productos sobre el módulo de cotización, por su interfaz pública (ARQ-01)."""

from __future__ import annotations

from app.modulos import cotizacion

from ..dominio.modelo import CoberturaPoliza, CondicionesDeCatalogo


class CatalogoDelNucleo:
    def condiciones(self, producto: str) -> CondicionesDeCatalogo | None:
        encontrado = cotizacion.producto_del_catalogo(producto)
        if encontrado is None:
            return None
        return CondicionesDeCatalogo(
            nombre_producto=encontrado.nombre,
            coberturas=tuple(CoberturaPoliza(c.nombre, c.limite) for c in encontrado.coberturas),
            deducible=encontrado.deducible,
            valor_asegurado=encontrado.valor_asegurado,
            prima_mensual=encontrado.prima_estandar,
        )
