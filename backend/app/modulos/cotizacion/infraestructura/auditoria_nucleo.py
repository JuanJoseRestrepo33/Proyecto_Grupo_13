"""Registro de decisiones sobre el log de auditoría real (ARQ-03).

Implementa el puerto `RegistroDeDecisiones` de cotización traduciendo cada decisión a un
`EventoAuditable` del módulo de auditoría. Solo importa la interfaz pública de ese módulo;
el contrato de frontera impide ir más adentro.
"""

from __future__ import annotations

from datetime import datetime

from app.modulos.auditoria import EventoAuditable, ServicioDeAuditoria

TIPO_DECISION = "decision_cotizacion"
CANAL_POR_DEFECTO = "api-v1"


class RegistroDeDecisionesEnAuditoria:
    def __init__(self, auditoria: ServicioDeAuditoria) -> None:
        self._auditoria = auditoria

    def registrar(
        self,
        *,
        id_cotizacion: str,
        id_consentimiento: str | None,
        id_cliente: str | None,
        version_regla: str,
        variables_entrada: dict[str, object],
        resultado: dict[str, object],
        ocurrido_en: datetime,
    ) -> str:
        referencias = {"id_cotizacion": id_cotizacion}
        if id_consentimiento:
            referencias["id_consentimiento"] = id_consentimiento
        if id_cliente:
            referencias["id_cliente"] = id_cliente
        evento = EventoAuditable(
            tipo=TIPO_DECISION,
            actor=id_cliente or "anonimo",
            canal=CANAL_POR_DEFECTO,
            ocurrido_en=ocurrido_en,
            version_regla=version_regla,
            variables_entrada=variables_entrada,
            resultado=resultado,
            referencias=referencias,
        )
        return self._auditoria.registrar(evento)
