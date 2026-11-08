"""Errores del checkout. Los códigos son los del contrato `bff-web`."""

from __future__ import annotations

from app.compartido.errores import DatoInvalido, RecursoNoEncontrado, ReglaDeNegocioViolada


class CotizacionNoEncontradaError(RecursoNoEncontrado):
    """No existe o no es del cliente: los dos casos responden igual."""

    codigo = "cotizacion_no_encontrada"


class CotizacionVencidaError(ReglaDeNegocioViolada):
    """Pasó la vigencia de la oferta; la interfaz ofrece recotizar (criterio 1)."""

    codigo = "cotizacion_vencida"


class SuscripcionNoEncontradaError(RecursoNoEncontrado):
    codigo = "suscripcion_no_encontrada"


class PagoNoAprobadoError(ReglaDeNegocioViolada):
    """Se pidió firmar sin un pago aprobado (criterio 4: primero se paga)."""

    codigo = "pago_no_aprobado"


class AceptacionRequeridaError(DatoInvalido):
    """La firma exige que el cliente acepte expresamente las condiciones."""

    codigo = "aceptacion_requerida"
