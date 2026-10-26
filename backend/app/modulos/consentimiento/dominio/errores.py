"""Errores del dominio de consentimiento.

Los códigos coinciden con los que el BFF web ya devuelve desde sus mocks, para que el
cliente web no tenga que distinguir de dónde vino el error.
"""

from __future__ import annotations

from app.compartido.errores import ErrorDeNegocio, ReglaDeNegocioViolada


class ConsentimientoNoVigenteError(ReglaDeNegocioViolada):
    codigo = "consentimiento_no_vigente"


class ConsentimientoRevocadoError(ReglaDeNegocioViolada):
    codigo = "consentimiento_revocado"


class ConsentimientoYaVigenteError(ReglaDeNegocioViolada):
    codigo = "consentimiento_ya_vigente"


class VersionDeTextoDesactualizadaError(ErrorDeNegocio):
    codigo = "version_texto_desactualizada"


class EntidadesInvalidasError(ErrorDeNegocio):
    codigo = "entidades_invalidas"


class CanalInvalidoError(ErrorDeNegocio):
    """El cambio no llegó por un canal del cliente (`web` o `movil`)."""

    codigo = "canal_invalido"
