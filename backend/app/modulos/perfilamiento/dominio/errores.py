"""Errores del dominio de perfilamiento."""

from __future__ import annotations

from app.compartido.errores import DependenciaNoDisponible, ReglaDeNegocioViolada


class ProveedorOpenFinanceNoDisponibleError(DependenciaNoDisponible):
    """El proveedor falló, no respondió dentro del presupuesto o el circuito está abierto.

    Es lo único que el núcleo ve del proveedor: nunca la excepción de su cliente HTTP
    (ARQ-02, criterio 4).
    """

    codigo = "proveedor_open_finance_no_disponible"


class PerfilSinConsentimientoError(ReglaDeNegocioViolada):
    """Nadie consulta datos financieros sin un consentimiento identificado (RC-04)."""

    codigo = "perfil_sin_consentimiento"
