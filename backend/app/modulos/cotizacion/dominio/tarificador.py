"""Motor de tarificación.

Reglas que implementa, todas de WEB-F01:

1. Sin consentimiento vigente la prima es la del catálogo. No se consultan datos
   financieros y el ahorro es cero.
2. Con consentimiento vigente se aplican los factores de la versión de regla activa.
3. Que el perfil venga de la caché (proveedor degradado) no cambia el precio, pero sí
   queda registrado: la oferta es igual de válida y el cliente merece saberlo.

El cálculo es **aditivo** sobre la prima base, que es exactamente como se presenta en la
pantalla "¿Por qué este precio?" del prototipo. Si fuera multiplicativo, la suma de los
porcentajes que ve el cliente no cuadraría con el total, y RC-08 exige que la explicación
reconstruya la decisión.

La versión de regla se guarda en cada oferta: dos cotizaciones del mismo cliente con
distinta versión son comparables porque cada una dice con qué reglas se calculó.
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass

from app.compartido.dinero import Dinero

from .catalogo import Producto
from .modelo import (
    BasePerfilamiento,
    DatosAsegurado,
    FactorAplicado,
    Oferta,
    PerfilDeRiesgo,
)

#: Versión activa del modelo de tarificación. Cambiarla es una decisión de negocio:
#: queda en el log de auditoría de cada oferta y permite reconstruirla después.
VERSION_REGLA = "rating-v3.2"

_FACTORES_OPEN_FINANCE: tuple[tuple[str, float], ...] = (
    ("Comportamiento de pago (Open Finance)", -0.06),
    ("Nivel de endeudamiento", -0.03),
    ("Siniestralidad de la zona (Open Data)", 0.01),
    ("Edad y ciudad", -0.02),
)

# Sin consentimiento la prima es exactamente la del catálogo, así que estos factores
# suman cero. No es un detalle cosmético: la regla de WEB-F01 dice "precio estándar",
# y la tarifa de catálogo ya incorpora edad, ciudad y siniestralidad de la zona. Un
# factor con efecto distinto de cero aquí haría que el precio estándar no fuera el
# estándar, y la explicación que ve el cliente dejaría de cuadrar con el total.
_FACTORES_ESTANDAR: tuple[tuple[str, float], ...] = (
    ("Edad y ciudad (incluidas en la tarifa base)", 0.0),
    ("Siniestralidad de la zona (incluida en la tarifa base)", 0.0),
    ("Sin datos financieros: no se aplican ajustes de Open Finance", 0.0),
)


@dataclass(frozen=True, slots=True)
class ResultadoTarificacion:
    """Lo que el tarificador produce, junto con lo que hizo falta para producirlo.

    `variables_entrada` es lo que se escribe en el log de decisión (RC-08): basta con
    eso, la versión de regla y el catálogo para recalcular la prima y obtener el mismo
    número.
    """

    oferta: Oferta
    variables_entrada: dict[str, object]


def _factores(base: BasePerfilamiento) -> tuple[FactorAplicado, ...]:
    crudos = _FACTORES_OPEN_FINANCE if base.uso_open_finance else _FACTORES_ESTANDAR
    return tuple(FactorAplicado(nombre, efecto) for nombre, efecto in crudos)


def _prima(prima_estandar: Dinero, factores: tuple[FactorAplicado, ...]) -> Dinero:
    efecto_total = sum(factor.efecto for factor in factores)
    return prima_estandar.escalar(1 + efecto_total)


def recalcular_prima(variables_entrada: Mapping[str, object]) -> Dinero:
    """Vuelve a calcular la prima mensual a partir de una decisión registrada (RC-08).

    Usa solo lo que quedó en el log: la prima estándar y el efecto de cada factor. No mira
    el catálogo ni la versión de regla activa, así que una decisión vieja se reconstruye
    con sus propios números aunque el tarifario haya cambiado después.
    """
    prima_estandar = Dinero.cop(int(variables_entrada["prima_estandar"]))  # type: ignore[call-overload]
    factores: Mapping[str, float] = variables_entrada["factores"]  # type: ignore[assignment]
    return prima_estandar.escalar(1 + sum(factores.values()))


def tarificar(
    producto: Producto,
    asegurado: DatosAsegurado,
    base: BasePerfilamiento,
    perfil: PerfilDeRiesgo | None = None,
) -> ResultadoTarificacion:
    """Calcula la oferta para un producto y una base de perfilamiento dadas.

    No decide si hay consentimiento: eso lo resuelve la capa de aplicación y llega aquí
    ya resuelto en `base`. El tarificador solo sabe de precios.
    """
    factores = _factores(base)
    oferta = Oferta(
        prima_mensual=_prima(producto.prima_estandar, factores),
        prima_estandar=producto.prima_estandar,
        valor_asegurado=producto.valor_asegurado,
        deducible=producto.deducible,
        coberturas=producto.coberturas,
        base_perfilamiento=base,
        version_regla=VERSION_REGLA,
        factores=factores,
    )
    variables = {
        "producto": str(producto.id),
        "prima_estandar": producto.prima_estandar.monto,
        "edad": asegurado.edad,
        "ciudad": asegurado.ciudad,
        "valor_asegurado_solicitado": asegurado.valor_asegurado,
        "base_perfilamiento": str(base),
        "factores": {factor.nombre: factor.efecto for factor in factores},
    }
    if perfil is not None:
        variables["perfil"] = {
            "puntaje": perfil.puntaje,
            "comportamiento_pago": perfil.comportamiento_pago,
            "nivel_endeudamiento": perfil.nivel_endeudamiento,
            "desde_cache": perfil.desde_cache,
        }
    return ResultadoTarificacion(oferta=oferta, variables_entrada=variables)
