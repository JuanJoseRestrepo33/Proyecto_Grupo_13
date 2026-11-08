"""Escritura de la aceptación y la firma en el log de auditoría (WEB-F03, criterio 5).

El pago lo audita pagos y la emisión la audita pólizas; la suscripción guarda la referencia
de las cuatro entradas en su trazabilidad.
"""

from __future__ import annotations

from app.modulos.auditoria import EventoAuditable, ServicioDeAuditoria

from ..dominio.modelo import Firma, Suscripcion
from .firma_simulada import huella_de_condiciones

TIPO_ACEPTACION = "suscripcion_aceptada"
TIPO_FIRMA = "suscripcion_firmada"


def _referencias(suscripcion: Suscripcion) -> dict[str, str]:
    return {
        "id_suscripcion": suscripcion.id_suscripcion,
        "id_cotizacion": suscripcion.id_cotizacion,
        "id_cliente": suscripcion.id_cliente,
    }


class RegistroEnAuditoria:
    def __init__(self, auditoria: ServicioDeAuditoria) -> None:
        self._auditoria = auditoria

    def registrar_aceptacion(self, suscripcion: Suscripcion) -> str:
        condiciones = suscripcion.condiciones
        return self._auditoria.registrar(
            EventoAuditable(
                tipo=TIPO_ACEPTACION,
                actor=suscripcion.id_cliente,
                canal=suscripcion.canal,
                ocurrido_en=suscripcion.aceptada_en,
                variables_entrada={
                    "producto": condiciones.producto,
                    "prima_mensual": condiciones.prima_mensual.monto,
                    "moneda": condiciones.prima_mensual.moneda,
                    "deducible": condiciones.deducible,
                    "valor_asegurado": condiciones.valor_asegurado,
                },
                resultado={"estado": str(suscripcion.estado)},
                referencias=_referencias(suscripcion),
            )
        )

    def registrar_firma(self, suscripcion: Suscripcion, firma: Firma) -> str:
        return self._auditoria.registrar(
            EventoAuditable(
                tipo=TIPO_FIRMA,
                actor=suscripcion.id_cliente,
                canal=suscripcion.canal,
                ocurrido_en=firma.firmada_en,
                variables_entrada={"huella_condiciones": huella_de_condiciones(suscripcion)},
                resultado={"id_firma": firma.id_firma, "proveedor": firma.proveedor},
                referencias={**_referencias(suscripcion), "id_firma": firma.id_firma},
            )
        )
