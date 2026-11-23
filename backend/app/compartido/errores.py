"""Errores de negocio del núcleo.

La fachada los traduce a respuestas HTTP. Los módulos nunca lanzan excepciones
de FastAPI: así el dominio se puede probar y reutilizar sin el framework.
"""

from __future__ import annotations


class ErrorDeNegocio(Exception):
    """Raíz de los errores que el núcleo expone a sus consumidores."""

    codigo = "error_de_negocio"

    def __init__(self, mensaje: str, **detalles: object) -> None:
        super().__init__(mensaje)
        self.mensaje = mensaje
        self.detalles = detalles


class RecursoNoEncontrado(ErrorDeNegocio):
    codigo = "recurso_no_encontrado"


class ReglaDeNegocioViolada(ErrorDeNegocio):
    codigo = "regla_de_negocio_violada"


class DependenciaNoDisponible(ErrorDeNegocio):
    """Un proveedor externo falló o no respondió a tiempo (ARQ-02, criterio 4).

    Es lo que un adaptador lanza en vez de dejar pasar la excepción de su cliente HTTP.
    El núcleo lo trata como un error del dominio y la fachada responde 503.
    """

    codigo = "dependencia_no_disponible"


class NoAutenticado(ErrorDeNegocio):
    """Las credenciales o el token no permiten identificar al cliente. La fachada responde 401.

    El mensaje nunca dice qué falló: si el correo no existe o si la contraseña no coincide
    se responde igual, para no revelar qué cuentas existen.
    """

    codigo = "no_autenticado"


class DatoInvalido(ErrorDeNegocio):
    """Un dato que pasó el contrato pero que el dominio rechaza. La fachada responde 422."""

    codigo = "dato_invalido"


class DemasiadasSolicitudes(ErrorDeNegocio):
    """Se repitió una operación antes de tiempo. La fachada responde 429.

    Si trae el detalle `reintentar_en_segundos`, la fachada lo pone en `Retry-After`.
    """

    codigo = "demasiadas_solicitudes"
