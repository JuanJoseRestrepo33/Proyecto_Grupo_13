"""Pruebas de la idempotencia de la cotización (WEB-F01, criterio 4).

La misma solicitud de un cliente, mientras su oferta siga vigente, devuelve la misma
cotización. Cualquier cambio que altere el precio (datos, preferencia o consentimiento)
produce una nueva.
"""

from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace

from app.modulos.cotizacion.aplicacion.servicio import SolicitudDeCotizacion, huella_de_solicitud
from app.modulos.cotizacion.dominio.modelo import (
    VIGENCIA_OFERTA,
    BasePerfilamiento,
    DatosAsegurado,
    ProductoId,
)

from .conftest import CLIENTE, CONSENTIMIENTO


def _solicitud(asegurado, id_cliente=CLIENTE, **opciones):
    return SolicitudDeCotizacion(
        producto=ProductoId.AUTO, asegurado=asegurado, id_cliente=id_cliente, **opciones
    )


def test_repetir_la_solicitud_devuelve_la_misma_cotizacion(servicio, asegurado, auditoria):
    primera = servicio.cotizar(_solicitud(asegurado))
    segunda = servicio.cotizar(_solicitud(asegurado))

    assert segunda.id_cotizacion == primera.id_cotizacion
    assert len(auditoria.entradas) == 1


def test_la_misma_solicitud_desde_otro_canal_es_la_misma(servicio, asegurado):
    web = servicio.cotizar(_solicitud(asegurado, canal="web"))
    movil = servicio.cotizar(_solicitud(asegurado, canal="movil"))

    assert movil.id_cotizacion == web.id_cotizacion


def test_la_ciudad_se_compara_sin_mayusculas_ni_espacios(servicio, asegurado):
    primera = servicio.cotizar(_solicitud(asegurado))
    otra_forma = replace(asegurado, ciudad="  bogotá ")

    assert servicio.cotizar(_solicitud(otra_forma)).id_cotizacion == primera.id_cotizacion


def test_otros_datos_dan_otra_cotizacion(servicio, asegurado):
    primera = servicio.cotizar(_solicitud(asegurado))
    mayor = DatosAsegurado(edad=asegurado.edad + 1, ciudad=asegurado.ciudad)

    assert servicio.cotizar(_solicitud(mayor)).id_cotizacion != primera.id_cotizacion
    assert (
        servicio.cotizar(_solicitud(asegurado, usar_open_finance=False)).id_cotizacion
        != primera.id_cotizacion
    )


def test_vencida_la_oferta_la_misma_solicitud_cotiza_de_nuevo(servicio, asegurado, reloj):
    primera = servicio.cotizar(_solicitud(asegurado))
    reloj.avanzar(VIGENCIA_OFERTA.total_seconds())

    segunda = servicio.cotizar(_solicitud(asegurado))

    assert segunda.id_cotizacion != primera.id_cotizacion


def test_revocar_entre_dos_solicitudes_no_reutiliza_la_oferta_personalizada(
    servicio, asegurado, consentimiento
):
    """RC-04 manda sobre la idempotencia: revocar se ve en la siguiente cotización."""
    consentimiento.otorgar(CLIENTE, CONSENTIMIENTO)
    con_datos = servicio.cotizar(_solicitud(asegurado))

    consentimiento.revocar(CLIENTE)
    sin_datos = servicio.cotizar(_solicitud(asegurado))

    assert sin_datos.id_cotizacion != con_datos.id_cotizacion
    assert sin_datos.oferta.base_perfilamiento is BasePerfilamiento.ESTANDAR


def test_la_reutilizacion_no_vuelve_a_pedir_el_perfil(
    servicio, asegurado, consentimiento, perfilamiento
):
    consentimiento.otorgar(CLIENTE, CONSENTIMIENTO)
    original = perfilamiento.perfil_de
    consultas: list[str] = []

    def contar(id_cliente, id_consentimiento):
        consultas.append(id_cliente)
        return original(id_cliente, id_consentimiento)

    perfilamiento.perfil_de = contar  # type: ignore[assignment]

    servicio.cotizar(_solicitud(asegurado))
    servicio.cotizar(_solicitud(asegurado))

    assert consultas == [CLIENTE]


def test_un_cliente_anonimo_no_es_idempotente(servicio, asegurado):
    """Sin cliente no hay de quién es la oferta: dos anónimos no comparten cotización."""
    primera = servicio.cotizar(_solicitud(asegurado, id_cliente=None))
    segunda = servicio.cotizar(_solicitud(asegurado, id_cliente=None))

    assert segunda.id_cotizacion != primera.id_cotizacion
    assert primera.huella is None


def test_dos_clientes_con_los_mismos_datos_no_comparten_cotizacion(servicio, asegurado):
    primera = servicio.cotizar(_solicitud(asegurado))
    otra = servicio.cotizar(_solicitud(asegurado, id_cliente="CLI-0002"))

    assert otra.id_cotizacion != primera.id_cotizacion


def test_solicitudes_simultaneas_dejan_una_sola_cotizacion(servicio, asegurado, auditoria):
    """El doble clic llega en paralelo, no uno detrás del otro."""
    with ThreadPoolExecutor(max_workers=8) as hilos:
        ids = set(
            hilos.map(lambda _: servicio.cotizar(_solicitud(asegurado)).id_cotizacion, range(16))
        )

    assert len(ids) == 1
    assert len(auditoria.entradas) == 1


def test_la_huella_incluye_el_consentimiento(asegurado):
    solicitud = _solicitud(asegurado)

    assert huella_de_solicitud(solicitud, None) != huella_de_solicitud(solicitud, CONSENTIMIENTO)
