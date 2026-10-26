"""Errores del dominio de siniestros. Los códigos son los que el BFF pasa a los canales."""

from __future__ import annotations

from app.compartido.errores import ErrorDeNegocio, RecursoNoEncontrado, ReglaDeNegocioViolada


class PolizaNoEncontradaError(RecursoNoEncontrado):
    """La póliza no existe o no es del cliente: los dos casos responden igual."""

    codigo = "poliza_no_encontrada"


class EventoFueraDeVigenciaError(ReglaDeNegocioViolada):
    """El evento ocurrió fuera de la vigencia de la póliza: no procede y no queda registro."""

    codigo = "evento_fuera_de_vigencia"


class FechaFuturaError(ErrorDeNegocio):
    codigo = "fecha_futura"


class ClaveIdempotenciaEnUsoError(ReglaDeNegocioViolada):
    """La clave ya se usó para un reporte con otros datos."""

    codigo = "clave_idempotencia_en_uso"


class SiniestroNoEncontradoError(RecursoNoEncontrado):
    codigo = "siniestro_no_encontrado"
