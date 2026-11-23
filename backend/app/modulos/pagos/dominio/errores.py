"""Errores del cobro. Los códigos son los que los canales muestran (contrato `bff-web`)."""

from __future__ import annotations

from app.compartido.errores import DatoInvalido, RecursoNoEncontrado, ReglaDeNegocioViolada


class PagoEnCursoError(ReglaDeNegocioViolada):
    """La misma clave llegó otra vez mientras la pasarela aún no respondía al primer intento."""

    codigo = "pago_en_curso"


class PagoYaRegistradoError(ReglaDeNegocioViolada):
    """Otra clave, pero el concepto ya tiene un pago aprobado, pendiente o en curso."""

    codigo = "pago_ya_registrado"


class ClaveDeOtroPagoError(DatoInvalido):
    """La clave ya identifica un intento de otro concepto o de otro cliente."""

    codigo = "clave_idempotencia_invalida"


class PagoNoEncontradoError(RecursoNoEncontrado):
    codigo = "pago_no_encontrado"
