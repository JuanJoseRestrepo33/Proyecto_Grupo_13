"""Pruebas del motor de tarificación y del modelo de dinero."""

from __future__ import annotations

import pytest

from app.compartido.dinero import Dinero, MonedasIncompatiblesError
from app.modulos.cotizacion.dominio import catalogo
from app.modulos.cotizacion.dominio.modelo import (
    BasePerfilamiento,
    DatosAsegurado,
    ProductoId,
)
from app.modulos.cotizacion.dominio.tarificador import VERSION_REGLA, tarificar

ASEGURADO = DatosAsegurado(edad=34, ciudad="Bogotá")


def test_sin_consentimiento_la_prima_es_la_del_catalogo():
    producto = catalogo.obtener(ProductoId.AUTO)

    oferta = tarificar(producto, ASEGURADO, BasePerfilamiento.ESTANDAR).oferta

    assert oferta.prima_mensual == producto.prima_estandar
    assert oferta.ahorro_mensual == Dinero.cop(0)
    assert oferta.base_perfilamiento is BasePerfilamiento.ESTANDAR
    assert oferta.base_perfilamiento.uso_open_finance is False


def test_con_open_finance_la_prima_baja_el_diez_por_ciento():
    """-6 % -3 % +1 % -2 % = -10 % sobre la prima base, redondeado a la centena."""
    producto = catalogo.obtener(ProductoId.AUTO)

    oferta = tarificar(producto, ASEGURADO, BasePerfilamiento.OPEN_FINANCE).oferta

    assert oferta.prima_estandar == Dinero.cop(148_000)
    assert oferta.prima_mensual == Dinero.cop(133_200)
    assert oferta.ahorro_mensual == Dinero.cop(14_800)


def test_la_suma_de_los_factores_explica_exactamente_la_prima():
    """RC-08: lo que ve el cliente en "¿por qué este precio?" reconstruye el número."""
    producto = catalogo.obtener(ProductoId.HOGAR)

    oferta = tarificar(producto, ASEGURADO, BasePerfilamiento.OPEN_FINANCE).oferta

    efecto_total = sum(factor.efecto for factor in oferta.factores)
    esperada = producto.prima_estandar.escalar(1 + efecto_total)
    assert oferta.prima_mensual == esperada


def test_el_perfil_desde_cache_no_cambia_el_precio_pero_si_la_base():
    """RC-03: el proveedor degradado no degrada la oferta, solo se declara."""
    producto = catalogo.obtener(ProductoId.VIAJE)

    normal = tarificar(producto, ASEGURADO, BasePerfilamiento.OPEN_FINANCE).oferta
    cache = tarificar(producto, ASEGURADO, BasePerfilamiento.OPEN_FINANCE_EN_CACHE).oferta

    assert cache.prima_mensual == normal.prima_mensual
    assert cache.base_perfilamiento is BasePerfilamiento.OPEN_FINANCE_EN_CACHE
    assert cache.base_perfilamiento.uso_open_finance is True


def test_la_oferta_declara_la_version_de_regla():
    producto = catalogo.obtener(ProductoId.DISPOSITIVOS)

    resultado = tarificar(producto, ASEGURADO, BasePerfilamiento.OPEN_FINANCE)

    assert resultado.oferta.version_regla == VERSION_REGLA
    assert resultado.variables_entrada["prima_estandar"] == 21_000
    assert resultado.variables_entrada["base_perfilamiento"] == "open_finance"


@pytest.mark.parametrize("producto", list(ProductoId))
def test_todo_producto_del_catalogo_se_puede_tarifar(producto: ProductoId):
    oferta = tarificar(catalogo.obtener(producto), ASEGURADO, BasePerfilamiento.OPEN_FINANCE).oferta

    assert oferta.prima_mensual.monto > 0
    assert oferta.prima_mensual <= oferta.prima_estandar
    assert len(oferta.coberturas) >= 2


def test_vida_hipotecario_no_se_lista_en_el_cotizador_web():
    """Se vende embebido en el banco socio, no en el canal web."""
    ids_web = {p.id for p in catalogo.listar(solo_web=True)}

    assert ProductoId.VIDA_HIPOTECARIO not in ids_web
    assert ProductoId.AUTO in ids_web


def test_el_ahorro_nunca_es_negativo():
    cero = Dinero.cop(1_000) - Dinero.cop(5_000)

    assert cero == Dinero.cop(0)


def test_no_se_pueden_mezclar_monedas():
    with pytest.raises(MonedasIncompatiblesError):
        Dinero(100, "COP") + Dinero(100, "USD")


def test_la_prima_se_redondea_a_la_centena():
    assert Dinero.cop(49_000).escalar(0.9).monto % 100 == 0
    assert Dinero.cop(21_000).escalar(0.9) == Dinero.cop(18_900)
