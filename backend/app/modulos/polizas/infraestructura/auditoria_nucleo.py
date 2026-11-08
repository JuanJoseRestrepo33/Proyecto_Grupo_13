"""Escritura de la emisión de pólizas en el log de auditoría (WEB-F03, criterio 5)."""

from __future__ import annotations

from app.modulos.auditoria import EventoAuditable, ServicioDeAuditoria

from ..dominio.modelo import Poliza

TIPO = "poliza_emitida"


class RegistroEnAuditoria:
    def __init__(self, auditoria: ServicioDeAuditoria) -> None:
        self._auditoria = auditoria

    def registrar_emision(self, poliza: Poliza) -> str:
        prima = poliza.prima_mensual
        return self._auditoria.registrar(
            EventoAuditable(
                tipo=TIPO,
                actor=poliza.id_cliente,
                canal=poliza.canal or "api-v1",
                ocurrido_en=poliza.emitida_en,  # type: ignore[arg-type]
                variables_entrada={
                    "producto": poliza.producto,
                    "prima_mensual": None if prima is None else prima.monto,
                    "moneda": None if prima is None else prima.moneda,
                    "deducible": poliza.deducible,
                    "valor_asegurado": poliza.valor_asegurado,
                    "coberturas": [c.nombre for c in poliza.coberturas],
                },
                resultado={
                    "numero": poliza.numero,
                    "inicio": poliza.inicio.isoformat(),
                    "fin": poliza.fin.isoformat(),
                    "id_documento": poliza.id_documento,
                },
                referencias={
                    "numero_poliza": poliza.numero,
                    "id_suscripcion": poliza.id_suscripcion or "",
                    "id_firma": poliza.id_firma or "",
                    "id_cliente": poliza.id_cliente,
                },
            )
        )
