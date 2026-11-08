"""Casos de uso de siniestros: reportar, consultar y seguir (WEB-F05, WEB-F06).

El reporte es el mismo en los dos canales: mismo servicio, misma referencia, misma clave de
idempotencia. La vigencia se valida contra el módulo de pólizas por su interfaz pública,
nunca leyendo sus datos (ARQ-01).
"""

from __future__ import annotations

import uuid
from dataclasses import replace

from app.compartido.eventos import BusDeEventos, Evento
from app.compartido.reloj import Reloj

from ..dominio.almacen_evidencias import AlmacenDeEvidencias
from ..dominio.errores import (
    ClaveIdempotenciaEnUsoError,
    EventoFueraDeVigenciaError,
    EvidenciaNoEncontradaError,
    FechaFuturaError,
    PolizaNoEncontradaError,
    SiniestroCerradoError,
    SiniestroNoEncontradoError,
)
from ..dominio.evidencias import Captura, EvidenciaDelSiniestro, exigir_evidencia_valida
from ..dominio.modelo import ESTADOS_CERRADOS, DatosDeReporte, EstadoSiniestro, Siniestro
from ..dominio.puertos import ConsultaDePolizas, RegistroDeAuditoria, RepositorioSiniestros
from ..dominio.seguimiento import (
    ACTOR_CLIENTE,
    ACTOR_OPERADOR,
    LARGO_MAXIMO_MOTIVO,
    CambioConcurrenteError,
    EventoDeSeguimiento,
    Requerimiento,
    RequerimientoNoEncontradoError,
    RequerimientosObligatoriosError,
    RequerimientoYaAtendidoError,
    exigir_transicion,
    limpiar_requerimientos,
)

#: Evento que publica un reporte nuevo. El seguimiento (WEB-F06) y las notificaciones
#: (MOB-F06) se suscriben a los eventos de siniestro.
EVENTO_SINIESTRO_REPORTADO = "siniestro-reportado"
#: Evento que publica cada cambio de estado (WEB-F06, criterio 3). Lo consume MOB-F06 para
#: el push. La carga trae el cliente, la referencia y los dos estados.
EVENTO_SINIESTRO_ESTADO_CAMBIADO = "siniestro-estado-cambiado"


class ServicioDeSiniestros:
    def __init__(
        self,
        repositorio: RepositorioSiniestros,
        polizas: ConsultaDePolizas,
        auditoria: RegistroDeAuditoria,
        bus: BusDeEventos,
        reloj: Reloj,
        almacen: AlmacenDeEvidencias,
    ) -> None:
        self._repositorio = repositorio
        self._polizas = polizas
        self._auditoria = auditoria
        self._almacen = almacen
        self._bus = bus
        self._reloj = reloj

    def reportar(
        self,
        id_cliente: str,
        datos: DatosDeReporte,
        canal: str,
        clave_idempotencia: str | None = None,
    ) -> tuple[Siniestro, bool]:
        """Crea el siniestro en estado `reportado`. Devuelve el siniestro y si es nuevo.

        Con la misma clave de idempotencia y los mismos datos devuelve el primero, sin crear
        otro. Con la misma clave y otros datos, `clave_idempotencia_en_uso`.

        Una póliza vencida admite el reporte si el evento ocurrió dentro de su vigencia
        (criterio 3). Si ocurrió fuera, se explica por qué no procede y no queda registro.
        """
        ahora = self._reloj.ahora()
        if datos.ocurrido_el > ahora.date():
            raise FechaFuturaError(
                "La fecha del evento no puede ser futura",
                ocurrido_el=datos.ocurrido_el.isoformat(),
            )
        poliza = self._polizas.poliza_del_cliente(datos.numero_poliza, id_cliente)
        if poliza is None:
            raise PolizaNoEncontradaError("La póliza no existe", numero_poliza=datos.numero_poliza)
        if not poliza.cubre(datos.ocurrido_el):
            raise EventoFueraDeVigenciaError(
                "El evento ocurrió fuera de la vigencia de la póliza",
                numero_poliza=poliza.numero,
                vigente_desde=poliza.inicio.isoformat(),
                vigente_hasta=poliza.fin.isoformat(),
            )

        candidato = Siniestro(
            id_siniestro=f"SIN-{uuid.uuid4()}",
            referencia=self._repositorio.siguiente_referencia(ahora.year),
            id_cliente=id_cliente,
            datos=datos,
            canal=canal,
            reportado_en=ahora,
            clave_idempotencia=clave_idempotencia,
        )
        guardado = self._repositorio.guardar_si_nuevo(candidato)
        if guardado.id_siniestro == candidato.id_siniestro:
            self._propagar(guardado)
            return guardado, True
        if guardado.datos.huella() != datos.huella():
            raise ClaveIdempotenciaEnUsoError(
                "La clave de idempotencia ya se usó para otro reporte",
                referencia=guardado.referencia,
            )
        return guardado, False

    def _propagar(self, siniestro: Siniestro) -> None:
        """Solo un reporte nuevo se audita y se publica: un reintento no es otro reporte."""
        self._auditoria.registrar(siniestro)
        self._bus.publicar(
            Evento(
                nombre=EVENTO_SINIESTRO_REPORTADO,
                carga={
                    "id_siniestro": siniestro.id_siniestro,
                    "referencia": siniestro.referencia,
                    "id_cliente": siniestro.id_cliente,
                    "numero_poliza": siniestro.datos.numero_poliza,
                    "tipo_evento": str(siniestro.datos.tipo_evento),
                    "canal": siniestro.canal,
                },
                ocurrido_en=siniestro.reportado_en,
            )
        )

    def obtener(self, id_cliente: str, id_siniestro: str) -> Siniestro:
        siniestro = self._repositorio.obtener(id_cliente, id_siniestro)
        if siniestro is None:
            raise SiniestroNoEncontradoError("El siniestro no existe", id_siniestro=id_siniestro)
        return siniestro

    def del_cliente(self, id_cliente: str) -> tuple[Siniestro, ...]:
        return self._repositorio.del_cliente(id_cliente)

    # -- evidencia (MOB-F05) -------------------------------------------------------------

    def adjuntar_evidencia(
        self,
        id_cliente: str,
        id_siniestro: str,
        tipo_contenido: str,
        contenido: bytes,
        nombre_original: str | None,
        canal: str,
        captura: Captura | None = None,
        id_requerimiento: str | None = None,
    ) -> EvidenciaDelSiniestro:
        """Guarda la evidencia en el almacén y la asocia al siniestro.

        Se puede agregar en el reporte o después, desde cualquiera de los dos canales, y se
        ve en ambos (criterio 5). Los límites se validan antes de tocar el almacén. Con
        `id_requerimiento`, la evidencia responde un requerimiento pendiente (WEB-F06,
        criterio 5) y lo deja atendido.
        """
        siniestro = self.obtener(id_cliente, id_siniestro)
        if siniestro.estado in ESTADOS_CERRADOS:
            raise SiniestroCerradoError(
                "El siniestro ya está cerrado", estado=str(siniestro.estado)
            )
        if id_requerimiento is not None:
            self._exigir_pendiente(siniestro.id_siniestro, id_requerimiento)
        tipo = exigir_evidencia_valida(tipo_contenido, contenido)
        ahora = self._reloj.ahora()
        captura = (captura or Captura()).validada(ahora)
        id_evidencia = f"EVI-{uuid.uuid4().hex}"
        guardada = self._almacen.guardar(
            siniestro.id_siniestro, id_evidencia, tipo, contenido, nombre_original
        )
        evidencia = EvidenciaDelSiniestro(
            id_evidencia=id_evidencia,
            id_siniestro=siniestro.id_siniestro,
            tipo_contenido=tipo,
            tamano=guardada.tamano,
            nombre_original=guardada.nombre_original,
            canal=canal,
            cargada_en=ahora,
            captura=captura,
        )
        self._repositorio.agregar_evidencia(evidencia)
        self._auditoria.registrar_evidencia(siniestro, evidencia)
        if id_requerimiento is not None:
            self._repositorio.atender_requerimiento(id_requerimiento, id_evidencia, ahora)
        return evidencia

    def evidencias(self, id_cliente: str, id_siniestro: str) -> tuple[EvidenciaDelSiniestro, ...]:
        siniestro = self.obtener(id_cliente, id_siniestro)
        return self._repositorio.evidencias(siniestro.id_siniestro)

    def archivo_de_evidencia(
        self, id_cliente: str, id_siniestro: str, id_evidencia: str
    ) -> tuple[EvidenciaDelSiniestro, bytes]:
        """La evidencia y su contenido, solo si es de un siniestro del cliente."""
        evidencia = next(
            (
                e
                for e in self.evidencias(id_cliente, id_siniestro)
                if e.id_evidencia == id_evidencia
            ),
            None,
        )
        archivo = None
        if evidencia is not None:
            archivo = self._almacen.leer(evidencia.id_siniestro, evidencia.id_evidencia)
        if evidencia is None or archivo is None:
            raise EvidenciaNoEncontradaError("La evidencia no existe", id_evidencia=id_evidencia)
        return evidencia, archivo.contenido

    # -- seguimiento (WEB-F06) -----------------------------------------------------------

    def cambiar_estado(
        self,
        id_siniestro: str,
        estado_nuevo: EstadoSiniestro,
        motivo: str | None = None,
        requerimientos: tuple[str, ...] | list[str] = (),
        actor: str = ACTOR_OPERADOR,
    ) -> Siniestro:
        """Cambia el estado del siniestro (operación interna, sin cliente en la ruta).

        Valida la transición, deja el evento en el historial, audita y publica
        `siniestro-estado-cambiado`. Pasar a `requiere_informacion` exige al menos un
        requerimiento. Si otro cambio llega primero, `cambio_de_estado_concurrente`.
        """
        siniestro = self._repositorio.por_id(id_siniestro)
        if siniestro is None:
            raise SiniestroNoEncontradoError("El siniestro no existe", id_siniestro=id_siniestro)
        exigir_transicion(siniestro.estado, estado_nuevo)
        pedidos = limpiar_requerimientos(tuple(requerimientos))
        if estado_nuevo is EstadoSiniestro.REQUIERE_INFORMACION and not pedidos:
            raise RequerimientosObligatoriosError("Para pedir información hay que decir qué falta")
        motivo = (motivo or "").strip()[:LARGO_MAXIMO_MOTIVO] or None

        ahora = self._reloj.ahora()
        anterior = siniestro.estado
        if not self._repositorio.cambiar_estado(id_siniestro, anterior, estado_nuevo, ahora):
            raise CambioConcurrenteError(
                "Otro cambio de estado llegó primero", id_siniestro=id_siniestro
            )
        actualizado = replace(siniestro, estado=estado_nuevo, estado_actualizado_en=ahora)
        evento = EventoDeSeguimiento(
            id_evento=f"EVS-{uuid.uuid4().hex}",
            id_siniestro=id_siniestro,
            estado_anterior=anterior,
            estado_nuevo=estado_nuevo,
            ocurrido_en=ahora,
            actor=actor,
            motivo=motivo,
        )
        evento = replace(
            evento,
            id_decision=self._auditoria.registrar_cambio_de_estado(actualizado, evento, pedidos),
        )
        self._repositorio.agregar_evento(evento)
        self._repositorio.agregar_requerimientos(
            tuple(
                Requerimiento(
                    id_requerimiento=f"REQ-{uuid.uuid4().hex}",
                    id_siniestro=id_siniestro,
                    descripcion=descripcion,
                    creado_en=ahora,
                    id_evento=evento.id_evento,
                )
                for descripcion in pedidos
            )
        )
        self._bus.publicar(
            Evento(
                nombre=EVENTO_SINIESTRO_ESTADO_CAMBIADO,
                carga={
                    "id_siniestro": id_siniestro,
                    "referencia": siniestro.referencia,
                    "id_cliente": siniestro.id_cliente,
                    "estado_anterior": str(anterior),
                    "estado_nuevo": str(estado_nuevo),
                    "id_evento": evento.id_evento,
                    "requerimientos": len(pedidos),
                },
                ocurrido_en=ahora,
            )
        )
        return actualizado

    def historial(self, id_cliente: str, id_siniestro: str) -> tuple[EventoDeSeguimiento, ...]:
        """Los eventos del siniestro con su fecha, empezando por el reporte (criterio 4)."""
        siniestro = self.obtener(id_cliente, id_siniestro)
        inicial = EventoDeSeguimiento(
            id_evento=f"EVS-{siniestro.id_siniestro}-reporte",
            id_siniestro=siniestro.id_siniestro,
            estado_anterior=None,
            estado_nuevo=EstadoSiniestro.REPORTADO,
            ocurrido_en=siniestro.reportado_en,
            actor=ACTOR_CLIENTE,
        )
        return (inicial, *self._repositorio.eventos(siniestro.id_siniestro))

    def requerimientos(self, id_cliente: str, id_siniestro: str) -> tuple[Requerimiento, ...]:
        siniestro = self.obtener(id_cliente, id_siniestro)
        return self._repositorio.requerimientos(siniestro.id_siniestro)

    def _exigir_pendiente(self, id_siniestro: str, id_requerimiento: str) -> Requerimiento:
        requerimiento = next(
            (
                r
                for r in self._repositorio.requerimientos(id_siniestro)
                if r.id_requerimiento == id_requerimiento
            ),
            None,
        )
        if requerimiento is None:
            raise RequerimientoNoEncontradoError(
                "El requerimiento no existe", id_requerimiento=id_requerimiento
            )
        if not requerimiento.pendiente:
            raise RequerimientoYaAtendidoError(
                "El requerimiento ya fue atendido", id_requerimiento=id_requerimiento
            )
        return requerimiento
