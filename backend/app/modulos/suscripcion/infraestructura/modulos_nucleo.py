"""Adaptadores hacia cotización, pagos y pólizas, sobre su interfaz pública (ARQ-01).

Traducen entre el modelo de cada módulo y lo que suscripción necesita saber de él, para que
el dominio de suscripción no dependa de los tipos de otro módulo.
"""

from __future__ import annotations

from collections.abc import Sequence
from datetime import datetime

from app.compartido.dinero import Dinero
from app.compartido.errores import RecursoNoEncontrado
from app.modulos import cotizacion, pagos, polizas

from ..dominio.errores import CotizacionNoEncontradaError, CotizacionVencidaError
from ..dominio.modelo import (
    CoberturaOfertada,
    CondicionesDeOferta,
    PagoDeLaPrima,
    PolizaEmitida,
    Suscripcion,
)


class OfertasDelNucleo:
    def __init__(self, cotizaciones: cotizacion.ServicioDeCotizacion) -> None:
        self._cotizaciones = cotizaciones

    def condiciones(
        self, id_cotizacion: str, id_cliente: str, ahora: datetime
    ) -> CondicionesDeOferta:
        try:
            cotizada = self._cotizaciones.obtener(id_cotizacion)
        except RecursoNoEncontrado:
            cotizada = None
        if cotizada is None or cotizada.id_cliente != id_cliente:
            raise CotizacionNoEncontradaError(
                "La cotización no existe", id_cotizacion=id_cotizacion
            )
        # La regla de vigencia es de cotización; aquí solo se traduce al código del checkout.
        try:
            cotizada.exigir_vigente(ahora)
        except cotizacion.OfertaVencidaError as error:
            raise CotizacionVencidaError(
                "La oferta venció; hay que recotizar",
                id_cotizacion=id_cotizacion,
                vencio_en=cotizada.expira_en.isoformat(),
            ) from error
        oferta = cotizada.oferta
        return CondicionesDeOferta(
            id_cotizacion=cotizada.id_cotizacion,
            producto=str(cotizada.producto),
            nombre_producto=cotizada.nombre_producto,
            prima_mensual=oferta.prima_mensual,
            valor_asegurado=oferta.valor_asegurado,
            deducible=oferta.deducible,
            coberturas=tuple(CoberturaOfertada(c.nombre, c.limite) for c in oferta.coberturas),
        )


def _a_pago(pago: pagos.Pago) -> PagoDeLaPrima:
    return PagoDeLaPrima(
        id_pago=pago.id_pago,
        estado=str(pago.estado),
        medio=str(pago.medio),
        monto=Dinero(pago.monto.monto, pago.monto.moneda),
        referencia=pago.referencia,
        creado_en=pago.creado_en,
        actualizado_en=pago.actualizado_en,
        id_decision=pago.id_decision,
    )


class CobrosDelNucleo:
    def __init__(self, servicio: pagos.ServicioDePagos) -> None:
        self._pagos = servicio

    def cobrar(
        self, suscripcion: Suscripcion, medio: str, clave_idempotencia: str, canal: str
    ) -> tuple[PagoDeLaPrima, bool]:
        pago, nuevo = self._pagos.cobrar(
            concepto=suscripcion.id_suscripcion,
            id_cliente=suscripcion.id_cliente,
            monto=suscripcion.condiciones.prima_mensual,
            medio=pagos.MedioPago(medio),
            clave_idempotencia=clave_idempotencia,
            canal=canal,
        )
        return _a_pago(pago), nuevo

    def consultar(self, suscripcion: Suscripcion, id_pago: str) -> PagoDeLaPrima:
        return _a_pago(self._pagos.consultar(suscripcion.id_suscripcion, id_pago))

    def ultimo(self, suscripcion: Suscripcion) -> PagoDeLaPrima | None:
        pago = self._pagos.ultimo(suscripcion.id_suscripcion)
        return None if pago is None else _a_pago(pago)

    def ultimos(self, suscripciones: Sequence[Suscripcion]) -> dict[str, PagoDeLaPrima]:
        pagos_ = self._pagos.ultimos([s.id_suscripcion for s in suscripciones])
        return {concepto: _a_pago(pago) for concepto, pago in pagos_.items()}


class EmisionDelNucleo:
    def __init__(self, servicio: polizas.ServicioDePolizas) -> None:
        self._polizas = servicio

    def emitir(self, suscripcion: Suscripcion) -> PolizaEmitida:
        if suscripcion.id_firma is None:
            raise ValueError(f"La suscripción {suscripcion.id_suscripcion} no está firmada")
        condiciones = suscripcion.condiciones
        poliza, _ = self._polizas.emitir(
            polizas.SolicitudDeEmision(
                id_suscripcion=suscripcion.id_suscripcion,
                id_cliente=suscripcion.id_cliente,
                producto=condiciones.producto,
                nombre_producto=condiciones.nombre_producto,
                coberturas=tuple(
                    polizas.CoberturaPoliza(c.nombre, c.limite) for c in condiciones.coberturas
                ),
                deducible=condiciones.deducible,
                valor_asegurado=condiciones.valor_asegurado,
                prima_mensual=condiciones.prima_mensual,
                id_firma=suscripcion.id_firma,
                canal=suscripcion.canal,
            )
        )
        return PolizaEmitida(poliza.numero, poliza.inicio, poliza.fin, poliza.id_decision)
