"""Errores del dominio de identidad.

Los códigos son los que el BFF móvil ya devuelve desde sus mocks
(`mobile/android/app/src/debug/.../MockSesiones.kt`), para que la app no distinga de dónde
vino el error.
"""

from __future__ import annotations

from app.compartido.errores import DependenciaNoDisponible, ReglaDeNegocioViolada


class IdentidadYaVerificadaError(ReglaDeNegocioViolada):
    codigo = "identidad_ya_verificada"


class VerificacionEnCursoError(ReglaDeNegocioViolada):
    codigo = "verificacion_en_curso"


class ProveedorKYCNoDisponibleError(DependenciaNoDisponible):
    """El proveedor de KYC/AML falló o no respondió a tiempo.

    El núcleo nunca ve la excepción del cliente HTTP del proveedor: el adaptador la
    traduce a esta (ARQ-02, criterio 4).
    """

    codigo = "proveedor_kyc_no_disponible"
