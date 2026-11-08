"""Casos de uso del checkout: aceptar, pagar, firmar y emitir (WEB-F03).

Suscripción orquesta y los demás módulos hacen su parte por su interfaz pública: cotización
dice si la oferta sigue vigente, pagos cobra sin cobro doble y pólizas emite con su
documento. Cada paso es idempotente, así que la web puede reintentar cualquiera sin miedo.
"""

from __future__ import annotations

import threading
import uuid
import zlib

from app.compartido.reloj import Reloj

from ..dominio.errores import (
    AceptacionRequeridaError,
    PagoNoAprobadoError,
    SuscripcionNoEncontradaError,
)
from ..dominio.modelo import EstadoSuscripcion, PagoDeLaPrima, Paso, Suscripcion
from ..dominio.puertos import (
    CobroDePrimas,
    ConsultaDeOfertas,
    EmisionDePolizas,
    FirmaElectronica,
    RegistroDeAuditoria,
    RepositorioSuscripciones,
)

#: Cerrojos por suscripción dentro del proceso: dos firmas simultáneas de la misma
#: suscripción no firman dos veces. Entre réplicas, la unicidad la da pólizas.
_CERROJOS = 64


class ServicioDeSuscripcion:
    def __init__(
        self,
        repositorio: RepositorioSuscripciones,
        ofertas: ConsultaDeOfertas,
        cobros: CobroDePrimas,
        firma: FirmaElectronica,
        emision: EmisionDePolizas,
        auditoria: RegistroDeAuditoria,
        reloj: Reloj,
    ) -> None:
        self._repositorio = repositorio
        self._ofertas = ofertas
        self._cobros = cobros
        self._firma = firma
        self._emision = emision
        self._auditoria = auditoria
        self._reloj = reloj
        self._cerrojos = tuple(threading.Lock() for _ in range(_CERROJOS))

    def _cerrojo(self, id_suscripcion: str) -> threading.Lock:
        return self._cerrojos[zlib.crc32(id_suscripcion.encode()) % _CERROJOS]

    # -- aceptar (criterio 1) ------------------------------------------------------------

    def aceptar(self, id_cliente: str, id_cotizacion: str, canal: str) -> tuple[Suscripcion, bool]:
        """Crea la suscripción `aceptada` con las condiciones de la oferta.

        Aceptar de nuevo la misma cotización devuelve la misma suscripción, aunque la oferta
        ya haya vencido: la aceptación ocurrió a tiempo. Una cotización vencida que nunca se
        aceptó responde `cotizacion_vencida`.
        """
        existente = self._repositorio.por_cotizacion(id_cotizacion)
        if existente is not None and existente.id_cliente == id_cliente:
            return existente, False

        ahora = self._reloj.ahora()
        condiciones = self._ofertas.condiciones(id_cotizacion, id_cliente, ahora)
        candidata = Suscripcion(
            id_suscripcion=f"SUS-{uuid.uuid4()}",
            id_cliente=id_cliente,
            condiciones=condiciones,
            canal=canal,
            aceptada_en=ahora,
        )
        guardada = self._repositorio.guardar_si_nueva(candidata)
        if guardada.id_suscripcion != candidata.id_suscripcion:
            return guardada, False
        id_decision = self._auditoria.registrar_aceptacion(guardada)
        guardada = guardada.con_paso(Paso.ACEPTACION, id_decision, ahora)
        self._repositorio.actualizar(guardada)
        return guardada, True

    # -- consultar -----------------------------------------------------------------------

    def obtener(self, id_cliente: str, id_suscripcion: str) -> Suscripcion:
        suscripcion = self._repositorio.obtener(id_suscripcion)
        if suscripcion is None or suscripcion.id_cliente != id_cliente:
            raise SuscripcionNoEncontradaError(
                "La suscripción no existe", id_suscripcion=id_suscripcion
            )
        return suscripcion

    def del_cliente(self, id_cliente: str) -> tuple[Suscripcion, ...]:
        return self._repositorio.del_cliente(id_cliente)

    def ultimo_pago(self, suscripcion: Suscripcion) -> PagoDeLaPrima | None:
        return self._cobros.ultimo(suscripcion)

    # -- pagar (criterios 2 y 3) ---------------------------------------------------------

    def pagar(
        self,
        id_cliente: str,
        id_suscripcion: str,
        medio: str,
        clave_idempotencia: str,
        canal: str,
    ) -> tuple[PagoDeLaPrima, bool]:
        """Cobra la prima una sola vez. Las reglas del cobro doble son de pagos:
        `pago_en_curso`, `pago_ya_registrado` y el reintento con la misma clave."""
        suscripcion = self.obtener(id_cliente, id_suscripcion)
        pago, nuevo = self._cobros.cobrar(suscripcion, medio, clave_idempotencia, canal)
        self._registrar_si_aprobado(suscripcion, pago)
        return pago, nuevo

    def consultar_pago(self, id_cliente: str, id_suscripcion: str, id_pago: str) -> PagoDeLaPrima:
        """El pago con su estado actual. Un pendiente que el banco aprueba deja la suscripción
        pagada."""
        suscripcion = self.obtener(id_cliente, id_suscripcion)
        pago = self._cobros.consultar(suscripcion, id_pago)
        self._registrar_si_aprobado(suscripcion, pago)
        return pago

    def _registrar_si_aprobado(self, suscripcion: Suscripcion, pago: PagoDeLaPrima) -> Suscripcion:
        if not pago.aprobado or suscripcion.estado is not EstadoSuscripcion.ACEPTADA:
            return suscripcion
        with self._cerrojo(suscripcion.id_suscripcion):
            actual = self._repositorio.obtener(suscripcion.id_suscripcion) or suscripcion
            pagada = actual.pagada(pago, self._reloj.ahora())
            if pagada is not actual:
                self._repositorio.actualizar(pagada)
        return pagada

    # -- firmar y emitir (criterios 4 y 5) -----------------------------------------------

    def firmar(
        self, id_cliente: str, id_suscripcion: str, acepta_condiciones: bool
    ) -> tuple[Suscripcion, bool]:
        """Firma electrónicamente y emite la póliza. Devuelve si la emisión es nueva.

        Exige un pago aprobado; un pago pendiente se consulta antes de decidir. Firmar una
        suscripción ya emitida devuelve la misma, con la misma póliza.
        """
        suscripcion = self.obtener(id_cliente, id_suscripcion)
        if suscripcion.estado is EstadoSuscripcion.EMITIDA:
            return suscripcion, False
        if not acepta_condiciones:
            raise AceptacionRequeridaError("Para firmar hay que aceptar las condiciones")

        with self._cerrojo(suscripcion.id_suscripcion):
            suscripcion = self.obtener(id_cliente, id_suscripcion)
            if suscripcion.estado is EstadoSuscripcion.EMITIDA:
                return suscripcion, False
            suscripcion = self._confirmar_pago(suscripcion)
            if suscripcion.estado is not EstadoSuscripcion.PAGADA:
                raise PagoNoAprobadoError(
                    "La suscripción no tiene un pago aprobado", estado=str(suscripcion.estado)
                )
            firma = self._firma.firmar(suscripcion)
            id_decision_firma = self._auditoria.registrar_firma(suscripcion, firma)
            poliza = self._emision.emitir(suscripcion, firma)
            emitida = suscripcion.emitida(firma, id_decision_firma, poliza, self._reloj.ahora())
            self._repositorio.actualizar(emitida)
        return emitida, True

    def _confirmar_pago(self, suscripcion: Suscripcion) -> Suscripcion:
        """Si el último pago quedó pendiente, se pregunta a la pasarela antes de firmar."""
        if suscripcion.estado is not EstadoSuscripcion.ACEPTADA:
            return suscripcion
        pago = self._cobros.ultimo(suscripcion)
        if pago is not None and pago.pendiente:
            pago = self._cobros.consultar(suscripcion, pago.id_pago)
        if pago is None or not pago.aprobado:
            return suscripcion
        pagada = suscripcion.pagada(pago, self._reloj.ahora())
        self._repositorio.actualizar(pagada)
        return pagada
