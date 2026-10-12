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
