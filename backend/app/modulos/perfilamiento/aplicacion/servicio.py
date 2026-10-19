"""Casos de uso de perfilamiento."""

from __future__ import annotations

from ..dominio.errores import PerfilSinConsentimientoError
from ..dominio.modelo import RespuestaPerfil
from ..dominio.puertos import FuenteDePerfiles


class ServicioDePerfilamiento:
    """Entrega el perfil de riesgo de un cliente que autorizó el uso de sus datos.

    No verifica el consentimiento: eso lo hace quien llama, antes, contra el módulo de
    consentimiento. Lo que sí exige es que venga identificado: una consulta a Open Finance
    sin `id_consentimiento` no se hace (RC-04).
    """

    def __init__(self, adaptador: FuenteDePerfiles) -> None:
        self._adaptador = adaptador

    def perfil_de(self, id_cliente: str, id_consentimiento: str) -> RespuestaPerfil:
        if not id_consentimiento:
            raise PerfilSinConsentimientoError(
                "No se consulta el perfil financiero sin un consentimiento vigente",
                id_cliente=id_cliente,
            )
        return self._adaptador.perfil_de(id_cliente, id_consentimiento)

    def metricas(self) -> dict[str, object]:
        return self._adaptador.resumen()
