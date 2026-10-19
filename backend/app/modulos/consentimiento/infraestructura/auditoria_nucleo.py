"""Escritura de los eventos de consentimiento en el log de auditoría (ARQ-03, criterio 6).

Cada otorgamiento, renovación, revocación o vencimiento queda con actor, finalidad, canal,
marca de tiempo y la versión del texto legal que el cliente aceptó.
"""

from __future__ import annotations

from app.modulos.auditoria import EventoAuditable, ServicioDeAuditoria

from ..dominio.modelo import Consentimiento, EventoConsentimiento


class RegistroEnAuditoria:
    def __init__(self, auditoria: ServicioDeAuditoria) -> None:
        self._auditoria = auditoria

    def registrar(self, consentimiento: Consentimiento, evento: EventoConsentimiento) -> str:
        return self._auditoria.registrar(
            EventoAuditable(
                tipo=f"consentimiento_{evento.tipo}",
                actor=consentimiento.id_cliente,
                canal=evento.canal,
                ocurrido_en=evento.ocurrido_en,
                variables_entrada={
                    "finalidad": str(consentimiento.finalidad),
                    "entidades_origen": list(consentimiento.entidades_origen),
                },
                resultado={
                    "estado": str(consentimiento.estado(evento.ocurrido_en)),
                    "vence_en": consentimiento.vence_en.isoformat(),
                },
                referencias={
                    "id_consentimiento": consentimiento.id_consentimiento,
                    "id_cliente": consentimiento.id_cliente,
                },
                version_texto_legal=evento.version_texto,
            )
        )
