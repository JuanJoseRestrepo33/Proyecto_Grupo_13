"""Casos de uso de notificaciones: registrar el dispositivo y avisar del cambio (MOB-F06).

El disparador no lo llama nadie: el módulo se suscribe al evento `siniestro-estado-cambiado`
que publica siniestros (WEB-F06). Así siniestros no sabe que el push existe, y un proveedor
de push caído nunca impide cambiar un estado (el bus aísla a cada suscriptor).
"""

from __future__ import annotations

import logging
import uuid
from collections.abc import Mapping
from dataclasses import replace

from app.compartido.errores import DatoInvalido, RecursoNoEncontrado
from app.compartido.eventos import Evento
from app.compartido.reloj import Reloj

from ..dominio.modelo import (
    LARGO_MAXIMO_TOKEN,
    Dispositivo,
    EstadoEntrega,
    Notificacion,
    Plataforma,
    id_de_dispositivo,
    texto_del_cambio,
)
from ..dominio.puertos import ProveedorPush, RepositorioDispositivos, RepositorioNotificaciones

#: Tipo de notificación que la app reconoce para abrir el detalle del siniestro.
TIPO_ESTADO_SINIESTRO = "siniestro_estado"
LIMITE_POR_DEFECTO = 50

log = logging.getLogger(__name__)


class TokenInvalidoError(DatoInvalido):
    codigo = "token_push_invalido"


class DispositivoNoEncontradoError(RecursoNoEncontrado):
    codigo = "dispositivo_no_encontrado"


class NotificacionNoEncontradaError(RecursoNoEncontrado):
    codigo = "notificacion_no_encontrada"


class ServicioDeNotificaciones:
    def __init__(
        self,
        dispositivos: RepositorioDispositivos,
        notificaciones: RepositorioNotificaciones,
        proveedor: ProveedorPush,
        reloj: Reloj,
    ) -> None:
        self._dispositivos = dispositivos
        self._notificaciones = notificaciones
        self._proveedor = proveedor
        self._reloj = reloj

    # -- dispositivos (criterio 4) -------------------------------------------------------

    def registrar_dispositivo(
        self, id_cliente: str, token: str, plataforma: Plataforma = Plataforma.ANDROID
    ) -> tuple[Dispositivo, bool]:
        """Registra el dispositivo para recibir push. Es idempotente por token."""
        token = (token or "").strip()
        if not token or len(token) > LARGO_MAXIMO_TOKEN or any(c.isspace() for c in token):
            raise TokenInvalidoError("El token de push no es válido")
        ahora = self._reloj.ahora()
        return self._dispositivos.registrar(
            Dispositivo(
                id_dispositivo=id_de_dispositivo(token),
                id_cliente=id_cliente,
                token=token,
                plataforma=plataforma,
                registrado_en=ahora,
                actualizado_en=ahora,
            )
        )

    def dar_de_baja(self, id_cliente: str, id_dispositivo: str) -> None:
        """Al cerrar sesión, el teléfono deja de recibir los push de esa cuenta."""
        if not self._dispositivos.dar_de_baja(id_cliente, id_dispositivo):
            raise DispositivoNoEncontradoError(
                "El dispositivo no existe", id_dispositivo=id_dispositivo
            )

    def dispositivos(self, id_cliente: str) -> tuple[Dispositivo, ...]:
        return self._dispositivos.del_cliente(id_cliente)

    # -- disparador (criterio 1) ---------------------------------------------------------

    def al_cambiar_estado(self, evento: Evento) -> Notificacion | None:
        """Manejador de `siniestro-estado-cambiado`: avisa a todos los dispositivos del
        cliente. El mismo evento dos veces no manda dos push."""
        carga: Mapping[str, object] = evento.carga
        id_cliente = str(carga["id_cliente"])
        referencia = str(carga["referencia"])
        estado = str(carga["estado_nuevo"])
        titulo, cuerpo = texto_del_cambio(referencia, estado)
        candidata = Notificacion(
            id_notificacion=f"NOT-{uuid.uuid4().hex}",
            id_cliente=id_cliente,
            id_evento=str(carga["id_evento"]),
            titulo=titulo,
            cuerpo=cuerpo,
            datos={
                "tipo": TIPO_ESTADO_SINIESTRO,
                "id_siniestro": str(carga["id_siniestro"]),
                "referencia": referencia,
                "estado": estado,
            },
            creada_en=evento.ocurrido_en,
        )
        notificacion, nueva = self._notificaciones.guardar_si_nueva(candidata)
        if not nueva:
            return notificacion
        return self._entregar(notificacion)

    def _entregar(self, notificacion: Notificacion) -> Notificacion:
        dispositivos = self._dispositivos.del_cliente(notificacion.id_cliente)
        entregadas = 0
        for dispositivo in dispositivos:
            try:
                self._proveedor.enviar(
                    dispositivo.token, notificacion.titulo, notificacion.cuerpo, notificacion.datos
                )
                entregadas += 1
            except Exception:  # noqa: BLE001 - un dispositivo que falla no detiene a los demás
                log.warning(
                    "No se pudo enviar el push %s a %s",
                    notificacion.id_notificacion,
                    dispositivo.id_dispositivo,
                )
        if not dispositivos:
            estado = EstadoEntrega.SIN_DISPOSITIVO
        elif entregadas:
            estado = EstadoEntrega.ENVIADA
        else:
            estado = EstadoEntrega.FALLIDA
        entregada = replace(notificacion, estado=estado, dispositivos=entregadas)
        self._notificaciones.actualizar(entregada)
        return entregada

    # -- consulta ------------------------------------------------------------------------

    def notificaciones(
        self, id_cliente: str, limite: int = LIMITE_POR_DEFECTO
    ) -> tuple[Notificacion, ...]:
        return self._notificaciones.del_cliente(id_cliente, max(1, min(limite, 100)))

    def marcar_leida(self, id_cliente: str, id_notificacion: str) -> None:
        if not self._notificaciones.marcar_leida(id_cliente, id_notificacion, self._reloj.ahora()):
            raise NotificacionNoEncontradaError(
                "La notificación no existe", id_notificacion=id_notificacion
            )
