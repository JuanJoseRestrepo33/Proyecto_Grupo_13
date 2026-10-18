"""Casos de uso del consentimiento: otorgar, consultar, revocar, renovar e histórico."""

from __future__ import annotations

import uuid
from datetime import datetime

from app.compartido.errores import RecursoNoEncontrado
from app.compartido.eventos import BusDeEventos, Evento
from app.compartido.reloj import Reloj

from ..dominio import catalogo
from ..dominio.catalogo import CodigoFinalidad, Finalidad
from ..dominio.errores import ConsentimientoYaVigenteError, VersionDeTextoDesactualizadaError
from ..dominio.modelo import (
    Consentimiento,
    EstadoDeConsentimiento,
    EventoConsentimiento,
    TipoEvento,
)
from ..dominio.puertos import RegistroDeAuditoria, RepositorioConsentimientos

#: Nombres de los eventos que el módulo publica en el bus. El de revocación es el que
#: sostiene RC-04: perfilamiento lo escucha para vaciar su caché.
EVENTO_OTORGADO = "consentimiento-otorgado"
EVENTO_REVOCADO = "consentimiento-revocado"
EVENTO_RENOVADO = "consentimiento-renovado"
EVENTO_VENCIDO = "consentimiento-vencido"

_NOMBRE_POR_TIPO = {
    TipoEvento.OTORGADO: EVENTO_OTORGADO,
    TipoEvento.REVOCADO: EVENTO_REVOCADO,
    TipoEvento.RENOVADO: EVENTO_RENOVADO,
    TipoEvento.VENCIDO: EVENTO_VENCIDO,
}


class ServicioDeConsentimiento:
    def __init__(
        self,
        repositorio: RepositorioConsentimientos,
        auditoria: RegistroDeAuditoria,
        bus: BusDeEventos,
        reloj: Reloj,
    ) -> None:
        self._repositorio = repositorio
        self._auditoria = auditoria
        self._bus = bus
        self._reloj = reloj

    # --- consulta -----------------------------------------------------------------------

    def finalidades(self) -> tuple[Finalidad, ...]:
        return catalogo.FINALIDADES

    def listar(self, id_cliente: str) -> tuple[Consentimiento, ...]:
        ahora = self._reloj.ahora()
        return tuple(self._al_dia(c, ahora) for c in self._repositorio.del_cliente(id_cliente))

    def obtener(self, id_cliente: str, id_consentimiento: str) -> Consentimiento:
        consentimiento = self._repositorio.obtener(id_consentimiento)
        if consentimiento is None or consentimiento.id_cliente != id_cliente:
            # No se distingue "no existe" de "no es tuyo": sería revelar que existe.
            raise RecursoNoEncontrado(
                f"No existe el consentimiento {id_consentimiento}",
                id_consentimiento=id_consentimiento,
            )
        return self._al_dia(consentimiento, self._reloj.ahora())

    def eventos(self, id_cliente: str, id_consentimiento: str) -> tuple[EventoConsentimiento, ...]:
        return self.obtener(id_cliente, id_consentimiento).eventos

    def consentimiento_vigente(
        self, id_cliente: str, finalidad: CodigoFinalidad | str
    ) -> EstadoDeConsentimiento:
        """Lo que cotización y suscripción preguntan antes de tocar datos financieros.

        Se responde desde el repositorio, nunca desde una copia: así una revocación es
        efectiva en la siguiente consulta, sin ventana (criterio 3).
        """
        ahora = self._reloj.ahora()
        codigo = CodigoFinalidad(finalidad)
        for consentimiento in self._repositorio.del_cliente(id_cliente):
            if consentimiento.finalidad is codigo and consentimiento.vigente(ahora):
                return EstadoDeConsentimiento(True, consentimiento.id_consentimiento)
        return EstadoDeConsentimiento(False)

    # --- transiciones -------------------------------------------------------------------

    def otorgar(
        self,
        *,
        id_cliente: str,
        finalidad: CodigoFinalidad | str,
        entidades_origen: tuple[str, ...] | list[str],
        version_texto: str,
        canal: str,
    ) -> Consentimiento:
        definicion = catalogo.finalidad(finalidad)
        entidades = catalogo.validar_entidades(entidades_origen)
        if version_texto != definicion.version_texto:
            raise VersionDeTextoDesactualizadaError(
                "El texto legal aceptado no es el vigente",
                version_aceptada=version_texto,
                version_vigente=definicion.version_texto,
            )
        vigente = self.consentimiento_vigente(id_cliente, definicion.codigo)
        if vigente.vigente:
            raise ConsentimientoYaVigenteError(
                "Ya existe un consentimiento vigente para esa finalidad",
                id_consentimiento=vigente.id_consentimiento,
            )
        consentimiento = Consentimiento.otorgar(
            id_consentimiento=f"CONSENT-{uuid.uuid4()}",
            id_cliente=id_cliente,
            finalidad=definicion.codigo,
            entidades_origen=entidades,
            canal=canal,
            ahora=self._reloj.ahora(),
            vigencia_meses=definicion.vigencia_meses,
            version_texto=definicion.version_texto,
        )
        return self._guardar_y_propagar(consentimiento)

    def revocar(self, id_cliente: str, id_consentimiento: str, canal: str) -> Consentimiento:
        actual = self.obtener(id_cliente, id_consentimiento)
        revocado = actual.revocar(self._reloj.ahora(), canal)
        return self._guardar_y_propagar(revocado, desde=len(actual.eventos))

    def renovar(self, id_cliente: str, id_consentimiento: str, canal: str) -> Consentimiento:
        actual = self.obtener(id_cliente, id_consentimiento)
        definicion = catalogo.finalidad(actual.finalidad)
        renovado = actual.renovar(
            self._reloj.ahora(), canal, definicion.vigencia_meses, definicion.version_texto
        )
        return self._guardar_y_propagar(renovado, desde=len(actual.eventos))

    # --- internos -----------------------------------------------------------------------

    def _al_dia(self, consentimiento: Consentimiento, ahora: datetime) -> Consentimiento:
        """Materializa el vencimiento si ocurrió desde la última vez que se miró."""
        actualizado = consentimiento.con_vencimiento_registrado(ahora)
        if actualizado is not consentimiento:
            self._guardar_y_propagar(actualizado, desde=len(consentimiento.eventos))
        return actualizado

    def _guardar_y_propagar(self, consentimiento: Consentimiento, desde: int = 0) -> Consentimiento:
        """Persiste y, por cada evento nuevo, escribe en auditoría y publica en el bus.

        El orden importa: primero el repositorio, que es la fuente de verdad que consulta
        `consentimiento_vigente`; después el log y el bus. Si estos dos fallan, la decisión
        del cliente ya está tomada y vigente.
        """
        self._repositorio.guardar(consentimiento)
        for evento in consentimiento.eventos[desde:]:
            self._auditoria.registrar(consentimiento, evento)
            self._bus.publicar(
                Evento(
                    nombre=_NOMBRE_POR_TIPO[evento.tipo],
                    carga={
                        "id_cliente": consentimiento.id_cliente,
                        "id_consentimiento": consentimiento.id_consentimiento,
                        "finalidad": str(consentimiento.finalidad),
                        "canal": evento.canal,
                    },
                    ocurrido_en=evento.ocurrido_en,
                )
            )
        return consentimiento
