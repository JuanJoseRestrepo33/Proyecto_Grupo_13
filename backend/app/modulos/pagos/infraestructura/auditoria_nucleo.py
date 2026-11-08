"""Escritura de los pagos en el log de auditoría (WEB-F03, criterio 5).

Queda cada resultado de la pasarela: el aprobado, el rechazado y el pendiente, y la
confirmación posterior de un pendiente. Ningún dato del medio de pago llega al núcleo, así
que tampoco al log.
"""

from __future__ import annotations

from app.modulos.auditoria import EventoAuditable, ServicioDeAuditoria

from ..dominio.modelo import Pago

TIPO = "pago_procesado"


class RegistroEnAuditoria:
    def __init__(self, auditoria: ServicioDeAuditoria) -> None:
        self._auditoria = auditoria

    def registrar(self, pago: Pago) -> str:
        return self._auditoria.registrar(
            EventoAuditable(
                tipo=TIPO,
                actor=pago.id_cliente,
                canal=pago.canal,
                ocurrido_en=pago.actualizado_en or pago.creado_en,
                variables_entrada={
                    "medio": str(pago.medio),
                    "monto": pago.monto.monto,
                    "moneda": pago.monto.moneda,
                },
                resultado={"estado": str(pago.estado), "referencia": pago.referencia},
                referencias={
                    "id_pago": pago.id_pago,
                    "concepto": pago.concepto,
                    "id_cliente": pago.id_cliente,
                },
            )
        )
