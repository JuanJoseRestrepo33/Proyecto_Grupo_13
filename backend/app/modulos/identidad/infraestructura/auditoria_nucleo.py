"""Escritura de los intentos de verificación en el log de auditoría (MOB-F01, criterio 9).

Cada intento queda con resultado, proveedor, canal y marca de tiempo. El número de
documento no se escribe: el log es para demostrar qué se decidió, no para guardar datos
personales que ya están en la cuenta.
"""

from __future__ import annotations

from datetime import datetime

from app.modulos.auditoria import EventoAuditable, ServicioDeAuditoria

from ..dominio.modelo import VerificacionIdentidad


class RegistroEnAuditoria:
    def __init__(self, auditoria: ServicioDeAuditoria) -> None:
        self._auditoria = auditoria

    def registrar(self, verificacion: VerificacionIdentidad, suceso: str) -> str:
        return self._auditoria.registrar(
            EventoAuditable(
                tipo=f"verificacion_identidad_{suceso}",
                actor=verificacion.id_cliente,
                canal=verificacion.canal,
                ocurrido_en=verificacion.actualizada_en,
                variables_entrada={
                    "tipo_documento": str(verificacion.datos.tipo_documento),
                },
                resultado={
                    "estado": str(verificacion.estado),
                    "motivo": str(verificacion.motivo) if verificacion.motivo else None,
                    "reintento_permitido": verificacion.reintento_permitido,
                    "proveedor": verificacion.proveedor,
                    "referencia_proveedor": verificacion.referencia_proveedor,
                },
                referencias={
                    "id_verificacion": verificacion.id_verificacion,
                    "id_cliente": verificacion.id_cliente,
                },
            )
        )


class RegistroDeAccesosEnAuditoria:
    """Registros, verificaciones del correo e ingresos (contrato móvil, "Auditoría").

    El tipo es `acceso_<suceso>`. Un ingreso fallido de un correo desconocido queda con actor
    `desconocido`: el log no guarda el correo que se intentó.
    """

    def __init__(self, auditoria: ServicioDeAuditoria) -> None:
        self._auditoria = auditoria

    def registrar(
        self,
        suceso: str,
        *,
        id_cliente: str | None,
        canal: str,
        ocurrido_en: datetime,
        resultado: dict[str, object],
    ) -> str:
        referencias = {"id_cliente": id_cliente} if id_cliente else {}
        return self._auditoria.registrar(
            EventoAuditable(
                tipo=f"acceso_{suceso}",
                actor=id_cliente or "desconocido",
                canal=canal,
                ocurrido_en=ocurrido_en,
                resultado=resultado,
                referencias=referencias,
            )
        )
