"""Pruebas de los casos de uso: consentimiento, vigencia, recotización y auditoría."""

from __future__ import annotations

import pytest

from app.compartido.errores import RecursoNoEncontrado
from app.modulos.cotizacion.aplicacion.servicio import SolicitudDeCotizacion
from app.modulos.cotizacion.dominio.modelo import (
    VIGENCIA_OFERTA,
    BasePerfilamiento,
    EstadoCotizacion,
    OfertaVencidaError,
    ProductoId,
)

from .conftest import CLIENTE, CONSENTIMIENTO


def _solicitud(asegurado, id_cliente=None):
    return SolicitudDeCotizacion(
        producto=ProductoId.AUTO, asegurado=asegurado, id_cliente=id_cliente
    )


def test_cliente_anonimo_cotiza_a_precio_estandar(servicio, asegurado):
    """Sin identidad no hay consentimiento que verificar."""
    cotizacion = servicio.cotizar(_solicitud(asegurado))

    assert cotizacion.oferta.base_perfilamiento is BasePerfilamiento.ESTANDAR
    assert cotizacion.id_consentimiento is None
    assert cotizacion.oferta.ahorro_mensual.monto == 0


def test_cliente_sin_consentimiento_vigente_cotiza_a_precio_estandar(servicio, asegurado):
    cotizacion = servicio.cotizar(_solicitud(asegurado, CLIENTE))

    assert cotizacion.oferta.base_perfilamiento is BasePerfilamiento.ESTANDAR
    assert cotizacion.perfil is None


def test_con_consentimiento_vigente_se_aplica_open_finance(servicio, asegurado, consentimiento):
    consentimiento.otorgar(CLIENTE, CONSENTIMIENTO)

    cotizacion = servicio.cotizar(_solicitud(asegurado, CLIENTE))

    assert cotizacion.oferta.base_perfilamiento is BasePerfilamiento.OPEN_FINANCE
    assert cotizacion.id_consentimiento == CONSENTIMIENTO
    assert cotizacion.oferta.ahorro_mensual.monto > 0


def test_revocar_el_consentimiento_devuelve_el_precio_estandar(servicio, asegurado, consentimiento):
    """RC-04: revocar implica dejar de usar el dato en la cotización siguiente."""
    consentimiento.otorgar(CLIENTE, CONSENTIMIENTO)
    con_datos = servicio.cotizar(_solicitud(asegurado, CLIENTE))

    consentimiento.revocar(CLIENTE)
    sin_datos = servicio.cotizar(_solicitud(asegurado, CLIENTE))

    assert con_datos.oferta.base_perfilamiento is BasePerfilamiento.OPEN_FINANCE
    assert sin_datos.oferta.base_perfilamiento is BasePerfilamiento.ESTANDAR
    assert sin_datos.oferta.prima_mensual == sin_datos.oferta.prima_estandar


def test_sin_consentimiento_no_se_consulta_el_perfil(servicio, asegurado, perfilamiento):
    """El orden importa: verificar primero, consultar después. Nunca al revés."""
    consultas: list[str] = []
    perfilamiento.perfil_de = lambda c, k: consultas.append(c)  # type: ignore[assignment]

    servicio.cotizar(_solicitud(asegurado, CLIENTE))

    assert consultas == []


def test_proveedor_degradado_entrega_oferta_desde_cache(
    servicio, asegurado, consentimiento, perfilamiento
):
    """RC-03: el proveedor lento no deja al cliente sin oferta."""
    consentimiento.otorgar(CLIENTE, CONSENTIMIENTO)
    perfilamiento.degradado = True

    cotizacion = servicio.cotizar(_solicitud(asegurado, CLIENTE))

    assert cotizacion.oferta.base_perfilamiento is BasePerfilamiento.OPEN_FINANCE_EN_CACHE
    assert cotizacion.perfil is not None
    assert cotizacion.perfil.desde_cache is True


def test_sin_perfil_ni_cache_se_cotiza_igual_a_precio_estandar(
    servicio, asegurado, consentimiento, perfilamiento
):
    consentimiento.otorgar(CLIENTE, CONSENTIMIENTO)
    perfilamiento.sin_perfil = True

    cotizacion = servicio.cotizar(_solicitud(asegurado, CLIENTE))

    assert cotizacion.oferta.base_perfilamiento is BasePerfilamiento.ESTANDAR


# -- vigencia de la oferta --------------------------------------------------


def test_la_oferta_vence_a_los_cinco_minutos(servicio, asegurado, reloj):
    cotizacion = servicio.cotizar(_solicitud(asegurado))

    assert cotizacion.estado(reloj.ahora()) is EstadoCotizacion.VIGENTE
    assert cotizacion.segundos_restantes(reloj.ahora()) == 300

    reloj.avanzar(VIGENCIA_OFERTA.total_seconds())

    assert cotizacion.estado(reloj.ahora()) is EstadoCotizacion.VENCIDA
    assert cotizacion.segundos_restantes(reloj.ahora()) == 0


def test_una_oferta_vencida_no_se_puede_tomar(servicio, asegurado, reloj):
    cotizacion = servicio.cotizar(_solicitud(asegurado))
    reloj.avanzar(301)

    with pytest.raises(OfertaVencidaError) as error:
        cotizacion.exigir_vigente(reloj.ahora())

    assert error.value.codigo == "oferta_vencida"


def test_recotizar_crea_una_cotizacion_nueva_sin_tocar_la_anterior(servicio, asegurado, reloj):
    original = servicio.cotizar(_solicitud(asegurado))
    reloj.avanzar(301)

    nueva = servicio.recotizar(original.id_cotizacion)

    assert nueva.id_cotizacion != original.id_cotizacion
    assert nueva.id_cotizacion_origen == original.id_cotizacion
    assert nueva.producto == original.producto
    assert nueva.estado(reloj.ahora()) is EstadoCotizacion.VIGENTE
    assert servicio.obtener(original.id_cotizacion).estado(reloj.ahora()) is (
        EstadoCotizacion.VENCIDA
    )


def test_obtener_una_cotizacion_inexistente_falla(servicio):
    with pytest.raises(RecursoNoEncontrado):
        servicio.obtener("QT-2026-99999")


def test_los_identificadores_son_consecutivos_y_legibles(servicio, asegurado):
    primera = servicio.cotizar(_solicitud(asegurado))
    segunda = servicio.cotizar(_solicitud(asegurado))

    assert primera.id_cotizacion == "QT-2026-08831"
    assert segunda.id_cotizacion == "QT-2026-08832"


# -- trazabilidad (RC-08) ---------------------------------------------------


def test_cada_cotizacion_deja_una_decision_auditada(servicio, asegurado, consentimiento, auditoria):
    consentimiento.otorgar(CLIENTE, CONSENTIMIENTO)

    cotizacion = servicio.cotizar(_solicitud(asegurado, CLIENTE))

    assert len(auditoria.entradas) == 1
    entrada = auditoria.entradas[0]
    assert entrada["id_cotizacion"] == cotizacion.id_cotizacion
    assert entrada["id_consentimiento"] == CONSENTIMIENTO
    assert entrada["version_regla"] == cotizacion.oferta.version_regla
    assert entrada["variables_entrada"]["edad"] == asegurado.edad
    assert entrada["resultado"]["prima_mensual"] == cotizacion.oferta.prima_mensual.monto


def test_la_decision_registrada_permite_recalcular_la_prima(
    servicio, asegurado, consentimiento, auditoria
):
    """Con la versión de regla y las variables de entrada se reconstruye el número."""
    consentimiento.otorgar(CLIENTE, CONSENTIMIENTO)
    cotizacion = servicio.cotizar(_solicitud(asegurado, CLIENTE))

    variables = auditoria.entradas[0]["variables_entrada"]
    efecto_total = sum(variables["factores"].values())
    recalculada = round(variables["prima_estandar"] * (1 + efecto_total) / 100) * 100

    assert recalculada == cotizacion.oferta.prima_mensual.monto


def test_recotizar_deja_su_propia_decision(servicio, asegurado, auditoria):
    original = servicio.cotizar(_solicitud(asegurado))
    servicio.recotizar(original.id_cotizacion)

    assert len(auditoria.entradas) == 2
    assert auditoria.entradas[0]["id_decision"] != auditoria.entradas[1]["id_decision"]
