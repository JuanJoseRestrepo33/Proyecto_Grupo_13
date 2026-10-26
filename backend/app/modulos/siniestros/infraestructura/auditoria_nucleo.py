"""Escritura de los reportes de siniestro en el log de auditoría (WEB-F05, criterio 5).

Queda la póliza, el tipo de evento, el día en que ocurrió, el canal y la marca de tiempo.
La descripción no se escribe: el log demuestra qué se decidió, no guarda el relato del
cliente, que ya está en el siniestro.
"""

from __future__ import annotations

from app.modulos.auditoria import EventoAuditable, ServicioDeAuditoria

from ..dominio.evidencias import EvidenciaDelSiniestro
from ..dominio.modelo import Siniestro

TIPO = "siniestro_reportado"
TIPO_EVIDENCIA = "evidencia_agregada"


class RegistroEnAuditoria:
    def __init__(self, auditoria: ServicioDeAuditoria) -> None:
        self._auditoria = auditoria

    def registrar(self, siniestro: Siniestro) -> str:
        datos = siniestro.datos
        return self._auditoria.registrar(
            EventoAuditable(
                tipo=TIPO,
                actor=siniestro.id_cliente,
                canal=siniestro.canal,
                ocurrido_en=siniestro.reportado_en,
                variables_entrada={
                    "numero_poliza": datos.numero_poliza,
                    "tipo_evento": str(datos.tipo_evento),
                    "ocurrido_el": datos.ocurrido_el.isoformat(),
                    "monto_estimado": datos.monto_estimado,
                },
                resultado={"referencia": siniestro.referencia, "estado": str(siniestro.estado)},
                referencias={
                    "id_siniestro": siniestro.id_siniestro,
                    "id_cliente": siniestro.id_cliente,
                    "numero_poliza": datos.numero_poliza,
                },
            )
        )

    def registrar_evidencia(self, siniestro: Siniestro, evidencia: EvidenciaDelSiniestro) -> str:
        """Sin el nombre original del archivo, que puede traer datos del cliente."""
        return self._auditoria.registrar(
            EventoAuditable(
                tipo=TIPO_EVIDENCIA,
                actor=siniestro.id_cliente,
                canal=evidencia.canal,
                ocurrido_en=evidencia.cargada_en,
                variables_entrada={
                    "tipo_contenido": evidencia.tipo_contenido,
                    "tamano": evidencia.tamano,
                },
                resultado={"id_evidencia": evidencia.id_evidencia},
                referencias={
                    "id_siniestro": siniestro.id_siniestro,
                    "id_evidencia": evidencia.id_evidencia,
                    "id_cliente": siniestro.id_cliente,
                },
            )
        )
