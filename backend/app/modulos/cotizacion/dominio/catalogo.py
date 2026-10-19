"""Catálogo de productos y sus tarifas base.

Los valores reproducen el tarifario del prototipo navegable (`web/assets/js/features.js`),
que es hoy la fuente funcional acordada con el equipo. Vive en el dominio y no en la base
de datos porque en el Sprint 1 el catálogo todavía no es editable: cuando lo sea, esta
estructura se sustituye por un repositorio sin tocar el tarificador.
"""

from __future__ import annotations

from dataclasses import dataclass

from app.compartido.dinero import Dinero
from app.compartido.errores import RecursoNoEncontrado

from .modelo import Cobertura, ProductoId


@dataclass(frozen=True, slots=True)
class Producto:
    id: ProductoId
    nombre: str
    nombre_corto: str
    prima_estandar: Dinero
    valor_asegurado: str
    deducible: str
    coberturas: tuple[Cobertura, ...]
    #: Los productos que no se venden por el canal web no se listan en el cotizador web.
    disponible_en_web: bool = True


def _coberturas(*pares: tuple[str, str]) -> tuple[Cobertura, ...]:
    return tuple(Cobertura(nombre, limite) for nombre, limite in pares)


CATALOGO: dict[ProductoId, Producto] = {
    ProductoId.AUTO: Producto(
        id=ProductoId.AUTO,
        nombre="Seguro de Auto — Plan Full",
        nombre_corto="Auto",
        prima_estandar=Dinero.cop(148_000),
        valor_asegurado="$60.000.000 COP",
        deducible="5% del valor asegurado",
        coberturas=_coberturas(
            ("Responsabilidad civil", "$1.200.000.000 COP"),
            ("Todo riesgo (daño y hurto)", "Deducible 5%"),
            ("Asistencia en vía 24/7", "Incluida"),
        ),
    ),
    ProductoId.HOGAR: Producto(
        id=ProductoId.HOGAR,
        nombre="Seguro de Hogar — Plan Protegido",
        nombre_corto="Hogar",
        prima_estandar=Dinero.cop(62_000),
        valor_asegurado="$250.000.000 COP",
        deducible="$500.000 COP",
        coberturas=_coberturas(
            ("Incendio y terremoto", "$250.000.000 COP"),
            ("Contenidos", "$40.000.000 COP"),
            ("Responsabilidad civil familiar", "$80.000.000 COP"),
        ),
    ),
    ProductoId.VIAJE: Producto(
        id=ProductoId.VIAJE,
        nombre="Seguro de Viaje — Plan Internacional",
        nombre_corto="Viaje",
        prima_estandar=Dinero.cop(49_000),
        valor_asegurado="$1.000.000 USD",
        deducible="$150.000 COP",
        coberturas=_coberturas(
            ("Asistencia médica", "$1.000.000 USD"),
            ("Equipaje perdido", "$1.000.000 COP"),
            ("Retraso de vuelo ≥3h (paramétrico)", "$350.000 COP"),
        ),
    ),
    ProductoId.DISPOSITIVOS: Producto(
        id=ProductoId.DISPOSITIVOS,
        nombre="Seguro de Dispositivos — Plan Total",
        nombre_corto="Dispositivos",
        prima_estandar=Dinero.cop(21_000),
        valor_asegurado="$3.200.000 COP",
        deducible="10% del daño",
        coberturas=_coberturas(
            ("Daño accidental", "$3.200.000 COP"),
            ("Robo", "$3.200.000 COP"),
            ("Pérdida", "$1.600.000 COP"),
        ),
    ),
    ProductoId.VIDA_HIPOTECARIO: Producto(
        id=ProductoId.VIDA_HIPOTECARIO,
        nombre="Vida Hipotecario — cobertura ajustada a tu perfil",
        nombre_corto="Vida Hipotecario",
        prima_estandar=Dinero.cop(24_800),
        valor_asegurado="Saldo de la deuda",
        deducible="Sin deducible",
        coberturas=_coberturas(
            ("Fallecimiento", "100% del saldo insoluto"),
            ("Incapacidad total permanente", "100% del saldo insoluto"),
        ),
        # Se vende embebido en el banco socio, no en el cotizador web.
        disponible_en_web=False,
    ),
}


def obtener(producto: ProductoId) -> Producto:
    try:
        return CATALOGO[producto]
    except KeyError as error:
        raise RecursoNoEncontrado(
            f"el producto {producto} no existe en el catálogo", producto=str(producto)
        ) from error


def listar(solo_web: bool = False) -> list[Producto]:
    productos = CATALOGO.values()
    if solo_web:
        return [p for p in productos if p.disponible_en_web]
    return list(productos)
