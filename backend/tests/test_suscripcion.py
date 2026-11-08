"""Pruebas de WEB-F03 sobre el servicio de suscripción, con los módulos reales en memoria.

Cotización, pagos y pólizas se arman con sus repositorios en memoria y la pasarela y la firma
simuladas; la auditoría es la real, para verificar el criterio 5 sobre el log mismo.
"""

from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime

import pytest

from app.compartido.errores import DependenciaNoDisponible
from app.compartido.reloj import RelojFijo
from app.modulos.auditoria.aplicacion.servicio import ServicioDeAuditoria
from app.modulos.auditoria.infraestructura.almacen_memoria import AlmacenEnMemoria
from app.modulos.cotizacion.aplicacion.servicio import (
    ServicioDeCotizacion,
    SolicitudDeCotizacion,
)
from app.modulos.cotizacion.dominio.modelo import DatosAsegurado, ProductoId
from app.modulos.cotizacion.infraestructura.adaptadores_simulados import (
    ConsentimientoSimulado,
    PerfilamientoSimulado,
    RegistroDeDecisionesEnMemoria,
)
from app.modulos.cotizacion.infraestructura.repositorio_memoria import (
    RepositorioEnMemoria as RepositorioCotizaciones,
)
from app.modulos.pagos.aplicacion.servicio import ServicioDePagos
from app.modulos.pagos.dominio.errores import PagoEnCursoError, PagoYaRegistradoError
from app.modulos.pagos.dominio.modelo import EstadoPago, MedioPago
from app.modulos.pagos.infraestructura.auditoria_nucleo import RegistroEnAuditoria as AuditoriaPagos
from app.modulos.pagos.infraestructura.pasarela_simulada import PasarelaSimulada
from app.modulos.pagos.infraestructura.repositorio_memoria import (
    RepositorioEnMemoria as RepositorioPagos,
)
from app.modulos.polizas.aplicacion.servicio import ServicioDePolizas
from app.modulos.polizas.infraestructura.almacen_documentos import AlmacenDeDocumentosEnMemoria
from app.modulos.polizas.infraestructura.auditoria_nucleo import (
    RegistroEnAuditoria as AuditoriaPolizas,
)
from app.modulos.polizas.infraestructura.documento_pdf import GeneradorPdf
from app.modulos.polizas.infraestructura.repositorio_memoria import (
    RepositorioEnMemoria as RepositorioPolizas,
)
from app.modulos.suscripcion.aplicacion.servicio import ServicioDeSuscripcion
from app.modulos.suscripcion.dominio.errores import (
    AceptacionRequeridaError,
    CotizacionNoEncontradaError,
    CotizacionVencidaError,
    PagoNoAprobadoError,
    SuscripcionNoEncontradaError,
)
from app.modulos.suscripcion.dominio.modelo import EstadoSuscripcion, Paso
from app.modulos.suscripcion.infraestructura.auditoria_nucleo import RegistroEnAuditoria
from app.modulos.suscripcion.infraestructura.firma_simulada import FirmaSimulada
from app.modulos.suscripcion.infraestructura.modulos_nucleo import (
    CobrosDelNucleo,
    EmisionDelNucleo,
    OfertasDelNucleo,
)
from app.modulos.suscripcion.infraestructura.repositorio_memoria import RepositorioEnMemoria

INSTANTE = datetime(2026, 11, 8, 15, 0, tzinfo=UTC)
CLIENTE = "CLI-2026-000318"


class Entorno:
    """El checkout armado con los módulos reales en memoria."""

    def __init__(self) -> None:
        self.reloj = RelojFijo(INSTANTE)
        self.auditoria = ServicioDeAuditoria(AlmacenEnMemoria())
        decisiones = RegistroDeDecisionesEnMemoria()
        self.cotizacion = ServicioDeCotizacion(
            repositorio=RepositorioCotizaciones(),
            consentimiento=ConsentimientoSimulado(),
            perfilamiento=PerfilamientoSimulado(),
            auditoria=decisiones,
            reloj=self.reloj,
            decisiones=decisiones,
        )
        self.pasarela = PasarelaSimulada()
        self.pagos = ServicioDePagos(
            RepositorioPagos(), self.pasarela, AuditoriaPagos(self.auditoria), self.reloj
        )
        self.documentos = AlmacenDeDocumentosEnMemoria()
        self.polizas = ServicioDePolizas(
            RepositorioPolizas(),
            self.reloj,
            almacen=self.documentos,
            generador=GeneradorPdf(),
            auditoria=AuditoriaPolizas(self.auditoria),
        )
        self.firma = FirmaSimulada(self.reloj)
        self.repositorio = RepositorioEnMemoria()
        self.servicio = ServicioDeSuscripcion(
            repositorio=self.repositorio,
            ofertas=OfertasDelNucleo(self.cotizacion),
            cobros=CobrosDelNucleo(self.pagos),
            firma=self.firma,
            emision=EmisionDelNucleo(self.polizas),
            auditoria=RegistroEnAuditoria(self.auditoria),
            reloj=self.reloj,
        )

    def cotizar(self, id_cliente: str = CLIENTE) -> str:
        cotizacion = self.cotizacion.cotizar(
            SolicitudDeCotizacion(
                producto=ProductoId.AUTO,
                asegurado=DatosAsegurado(edad=34, ciudad="Bogotá", valor_asegurado=60_000_000),
                id_cliente=id_cliente,
                usar_open_finance=False,
                canal="web",
            )
        )
        return cotizacion.id_cotizacion

    def aceptada(self):
        return self.servicio.aceptar(CLIENTE, self.cotizar(), "web")[0]

    def pagada(self):
        suscripcion = self.aceptada()
        self.servicio.pagar(
            CLIENTE, suscripcion.id_suscripcion, "tarjeta_credito", "clave-1", "web"
        )
        return self.servicio.obtener(CLIENTE, suscripcion.id_suscripcion)


@pytest.fixture
def entorno() -> Entorno:
    return Entorno()


# -- criterio 1: aceptar -----------------------------------------------------------------


def test_aceptar_copia_las_condiciones_de_la_oferta(entorno):
    id_cotizacion = entorno.cotizar()
    oferta = entorno.cotizacion.obtener(id_cotizacion).oferta

    suscripcion, nueva = entorno.servicio.aceptar(CLIENTE, id_cotizacion, "web")

    assert nueva
    assert suscripcion.estado is EstadoSuscripcion.ACEPTADA
    condiciones = suscripcion.condiciones
    assert condiciones.prima_mensual == oferta.prima_mensual
    assert condiciones.deducible == oferta.deducible
    assert [c.nombre for c in condiciones.coberturas] == [c.nombre for c in oferta.coberturas]
    assert [p.paso for p in suscripcion.trazabilidad] == [Paso.ACEPTACION]
    assert entorno.servicio.obtener(CLIENTE, suscripcion.id_suscripcion) == suscripcion


def test_aceptar_otra_vez_devuelve_la_misma_suscripcion_aunque_ya_venza(entorno):
    primera = entorno.aceptada()
    entorno.reloj.avanzar(10 * 60)

    repetida, nueva = entorno.servicio.aceptar(CLIENTE, primera.id_cotizacion, "web")

    assert not nueva
    assert repetida == primera
    assert len(entorno.servicio.del_cliente(CLIENTE)) == 1


def test_aceptaciones_simultaneas_dejan_una_suscripcion(entorno):
    id_cotizacion = entorno.cotizar()

    with ThreadPoolExecutor(8) as hilos:
        suscripciones = list(
            hilos.map(
                lambda _: entorno.servicio.aceptar(CLIENTE, id_cotizacion, "web")[0], range(8)
            )
        )

    assert len({s.id_suscripcion for s in suscripciones}) == 1


def test_una_cotizacion_vencida_se_rechaza(entorno):
    id_cotizacion = entorno.cotizar()
    entorno.reloj.avanzar(5 * 60)

    with pytest.raises(CotizacionVencidaError) as error:
        entorno.servicio.aceptar(CLIENTE, id_cotizacion, "web")

    assert error.value.codigo == "cotizacion_vencida"
    assert entorno.servicio.del_cliente(CLIENTE) == ()


@pytest.mark.parametrize("id_cotizacion", ["COT-NO-EXISTE", None])
def test_una_cotizacion_inexistente_o_ajena_no_existe(entorno, id_cotizacion):
    id_cotizacion = id_cotizacion or entorno.cotizar("CLI-OTRO")

    with pytest.raises(CotizacionNoEncontradaError):
        entorno.servicio.aceptar(CLIENTE, id_cotizacion, "web")


def test_la_suscripcion_de_otro_cliente_no_existe(entorno):
    suscripcion = entorno.aceptada()

    with pytest.raises(SuscripcionNoEncontradaError):
        entorno.servicio.obtener("CLI-OTRO", suscripcion.id_suscripcion)


# -- criterios 2 y 3: pagar ---------------------------------------------------------------


def test_el_pago_aprobado_deja_la_suscripcion_pagada(entorno):
    suscripcion = entorno.aceptada()

    pago, nuevo = entorno.servicio.pagar(
        CLIENTE, suscripcion.id_suscripcion, "tarjeta_credito", "clave-1", "web"
    )

    assert nuevo and pago.aprobado
    assert pago.monto == suscripcion.condiciones.prima_mensual
    pagada = entorno.servicio.obtener(CLIENTE, suscripcion.id_suscripcion)
    assert pagada.estado is EstadoSuscripcion.PAGADA
    assert pagada.id_pago == pago.id_pago
    assert [p.paso for p in pagada.trazabilidad] == [Paso.ACEPTACION, Paso.PAGO]
    assert entorno.servicio.ultimo_pago(pagada) == pago


def test_reintentar_el_pago_con_la_misma_clave_no_cobra_otra_vez(entorno):
    suscripcion = entorno.aceptada()
    primero, _ = entorno.servicio.pagar(
        CLIENTE, suscripcion.id_suscripcion, "tarjeta_credito", "clave-1", "web"
    )

    repetido, nuevo = entorno.servicio.pagar(
        CLIENTE, suscripcion.id_suscripcion, "tarjeta_credito", "clave-1", "web"
    )

    assert not nuevo and repetido == primero
    assert len(entorno.pasarela.cobros) == 1
    with pytest.raises(PagoYaRegistradoError):
        entorno.servicio.pagar(CLIENTE, suscripcion.id_suscripcion, "pse", "clave-2", "web")


def test_el_rechazo_deja_la_suscripcion_aceptada_y_permite_otro_intento(entorno):
    suscripcion = entorno.aceptada()
    entorno.pasarela.resultados[MedioPago.TARJETA_CREDITO] = EstadoPago.RECHAZADO

    rechazado, _ = entorno.servicio.pagar(
        CLIENTE, suscripcion.id_suscripcion, "tarjeta_credito", "clave-1", "web"
    )

    assert rechazado.estado == "rechazado"
    assert entorno.servicio.obtener(CLIENTE, suscripcion.id_suscripcion).estado is (
        EstadoSuscripcion.ACEPTADA
    )
    entorno.pasarela.resultados[MedioPago.TARJETA_CREDITO] = EstadoPago.APROBADO
    aprobado, nuevo = entorno.servicio.pagar(
        CLIENTE, suscripcion.id_suscripcion, "tarjeta_credito", "clave-2", "web"
    )
    assert nuevo and aprobado.aprobado


def test_pse_pendiente_queda_pagada_al_consultarlo(entorno):
    suscripcion = entorno.aceptada()
    pendiente, _ = entorno.servicio.pagar(CLIENTE, suscripcion.id_suscripcion, "pse", "c-1", "web")
    assert pendiente.pendiente
    assert entorno.servicio.obtener(CLIENTE, suscripcion.id_suscripcion).estado is (
        EstadoSuscripcion.ACEPTADA
    )

    confirmado = entorno.servicio.consultar_pago(
        CLIENTE, suscripcion.id_suscripcion, pendiente.id_pago
    )

    assert confirmado.aprobado
    assert entorno.servicio.obtener(CLIENTE, suscripcion.id_suscripcion).estado is (
        EstadoSuscripcion.PAGADA
    )


def test_pago_en_curso_llega_al_cliente(entorno, monkeypatch):
    suscripcion = entorno.aceptada()

    def en_curso(*_args, **_kwargs):
        raise PagoEnCursoError("El pago sigue en curso")

    monkeypatch.setattr(entorno.pagos, "cobrar", en_curso)
    with pytest.raises(PagoEnCursoError):
        entorno.servicio.pagar(CLIENTE, suscripcion.id_suscripcion, "pse", "c-1", "web")


# -- criterios 4 y 5: firmar, emitir y auditar ---------------------------------------------


def test_firmar_emite_la_poliza_con_su_documento(entorno):
    pagada = entorno.pagada()

    emitida, nueva = entorno.servicio.firmar(CLIENTE, pagada.id_suscripcion, True)

    assert nueva
    assert emitida.estado is EstadoSuscripcion.EMITIDA
    assert emitida.numero_poliza == "POL-2026-000001"
    assert emitida.id_firma.startswith("FIR-")
    poliza = entorno.polizas.poliza_del_cliente(emitida.numero_poliza, CLIENTE)
    assert poliza.id_suscripcion == emitida.id_suscripcion
    assert poliza.prima_mensual == pagada.condiciones.prima_mensual
    assert entorno.polizas.documento(poliza.numero, CLIENTE)[1].startswith(b"%PDF")


def test_el_log_tiene_la_aceptacion_el_pago_la_firma_y_la_emision(entorno):
    """Criterio 5, verificado sobre el log de auditoría real."""
    emitida, _ = entorno.servicio.firmar(CLIENTE, entorno.pagada().id_suscripcion, True)

    pasos = {p.paso: p.id_decision for p in emitida.trazabilidad}
    assert list(pasos) == [Paso.ACEPTACION, Paso.PAGO, Paso.FIRMA, Paso.EMISION]
    tipos = {paso: entorno.auditoria.reconstruir(id_).evento.tipo for paso, id_ in pasos.items()}
    assert tipos == {
        Paso.ACEPTACION: "suscripcion_aceptada",
        Paso.PAGO: "pago_procesado",
        Paso.FIRMA: "suscripcion_firmada",
        Paso.EMISION: "poliza_emitida",
    }
    for id_decision in pasos.values():
        registro = entorno.auditoria.reconstruir(id_decision)
        referencias = registro.evento.referencias
        assert emitida.id_suscripcion in (
            referencias.get("id_suscripcion"),
            referencias.get("concepto"),
        )
    assert entorno.auditoria.verificar_integridad().conforme


def test_firmar_otra_vez_devuelve_la_misma_poliza(entorno):
    pagada = entorno.pagada()
    primera, _ = entorno.servicio.firmar(CLIENTE, pagada.id_suscripcion, True)

    repetida, nueva = entorno.servicio.firmar(CLIENTE, pagada.id_suscripcion, True)

    assert not nueva and repetida == primera
    assert len(entorno.firma.firmadas) == 1
    assert len(entorno.polizas.del_cliente(CLIENTE)) == 1


def test_firmas_simultaneas_dejan_una_poliza(entorno):
    pagada = entorno.pagada()

    with ThreadPoolExecutor(6) as hilos:
        emitidas = list(
            hilos.map(
                lambda _: entorno.servicio.firmar(CLIENTE, pagada.id_suscripcion, True)[0],
                range(6),
            )
        )

    assert len({e.numero_poliza for e in emitidas}) == 1
    assert len(entorno.firma.firmadas) == 1


def test_sin_pago_aprobado_no_se_firma(entorno):
    suscripcion = entorno.aceptada()

    with pytest.raises(PagoNoAprobadoError):
        entorno.servicio.firmar(CLIENTE, suscripcion.id_suscripcion, True)
    assert entorno.firma.firmadas == []


def test_firmar_con_pse_pendiente_lo_confirma_antes(entorno):
    suscripcion = entorno.aceptada()
    entorno.servicio.pagar(CLIENTE, suscripcion.id_suscripcion, "pse", "c-1", "web")

    emitida, _ = entorno.servicio.firmar(CLIENTE, suscripcion.id_suscripcion, True)

    assert emitida.estado is EstadoSuscripcion.EMITIDA
    assert [p.paso for p in emitida.trazabilidad] == [
        Paso.ACEPTACION,
        Paso.PAGO,
        Paso.FIRMA,
        Paso.EMISION,
    ]


def test_firmar_con_un_pendiente_que_el_banco_rechaza_no_emite(entorno):
    suscripcion = entorno.aceptada()
    entorno.pasarela.pendiente_se_resuelve_en = EstadoPago.RECHAZADO
    entorno.servicio.pagar(CLIENTE, suscripcion.id_suscripcion, "pse", "c-1", "web")

    with pytest.raises(PagoNoAprobadoError):
        entorno.servicio.firmar(CLIENTE, suscripcion.id_suscripcion, True)


def test_firmar_exige_aceptar_las_condiciones(entorno):
    pagada = entorno.pagada()

    with pytest.raises(AceptacionRequeridaError):
        entorno.servicio.firmar(CLIENTE, pagada.id_suscripcion, False)


def test_con_la_firma_caida_no_se_emite_y_se_puede_reintentar(entorno):
    pagada = entorno.pagada()
    entorno.firma.caida = True

    with pytest.raises(DependenciaNoDisponible):
        entorno.servicio.firmar(CLIENTE, pagada.id_suscripcion, True)
    assert entorno.polizas.del_cliente(CLIENTE) == ()

    entorno.firma.caida = False
    emitida, nueva = entorno.servicio.firmar(CLIENTE, pagada.id_suscripcion, True)
    assert nueva and emitida.estado is EstadoSuscripcion.EMITIDA


def test_la_firma_simulada_se_configura_por_entorno():
    reloj = RelojFijo(INSTANTE)

    assert FirmaSimulada.desde_entorno(reloj, {"FIRMA_MODO": "caida"}).caida
    assert not FirmaSimulada.desde_entorno(reloj, {}).caida
    with pytest.raises(RuntimeError):
        FirmaSimulada.desde_entorno(reloj, {"FIRMA_MODO": "rota"})
