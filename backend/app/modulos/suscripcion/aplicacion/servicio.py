"""Casos de uso del checkout: aceptar, pagar, firmar y emitir (WEB-F03).

Suscripción orquesta y los demás módulos hacen su parte por su interfaz pública: cotización
dice si la oferta sigue vigente, pagos cobra sin cobro doble y pólizas emite con su
documento. Cada paso es idempotente, así que la web puede reintentar cualquiera sin miedo.
"""

from __future__ import annotations

import threading
import uuid
import zlib
from collections.abc import Callable, Sequence

from app.compartido.reloj import Reloj

from ..dominio.errores import (
    AceptacionRequeridaError,
    ConflictoDeEscrituraError,
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

#: Veces que se relee y se vuelve a decidir un cambio que perdió contra otra réplica.
_INTENTOS_DE_ESCRITURA = 3


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
            return self._con_aceptacion_auditada(existente), False

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
        return self._con_aceptacion_auditada(guardada), True

    def _con_aceptacion_auditada(self, suscripcion: Suscripcion) -> Suscripcion:
        """Registra la aceptación si todavía no está en la trazabilidad. Así, si la auditoría
        o la escritura fallaron tras crear la suscripción, el reintento la completa."""
        if any(p.paso is Paso.ACEPTACION for p in suscripcion.trazabilidad):
            return suscripcion
        id_decision = self._auditoria.registrar_aceptacion(suscripcion)
        return self._cambiar(
            suscripcion,
            lambda s: s.con_paso(Paso.ACEPTACION, id_decision, s.aceptada_en),
        )

    def _cambiar(
        self, suscripcion: Suscripcion, cambio: Callable[[Suscripcion], Suscripcion]
    ) -> Suscripcion:
        """Aplica `cambio` y lo guarda con control optimista.

        Si otra réplica escribió entre la lectura y la escritura, se relee y el cambio se
        vuelve a decidir sobre lo más reciente. Los cambios no retroceden el estado
        (`pagada` sobre una emitida no hace nada), así que una escritura tardía nunca deshace
        una emisión.
        """
        actual = suscripcion
        for _ in range(_INTENTOS_DE_ESCRITURA):
            nueva = cambio(actual)
            if nueva == actual:
                return actual
            guardada = self._repositorio.actualizar(nueva)
            if guardada is not None:
                return guardada
            releida = self._repositorio.obtener(suscripcion.id_suscripcion)
            if releida is None:
                break
            actual = releida
        raise ConflictoDeEscrituraError(
            "La suscripción cambió mientras se guardaba; reintenta",
            id_suscripcion=suscripcion.id_suscripcion,
        )

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

    def ultimos_pagos(self, suscripciones: Sequence[Suscripcion]) -> dict[str, PagoDeLaPrima]:
        """El último pago de cada suscripción, en una sola consulta (para listar)."""
        return self._cobros.ultimos(suscripciones) if suscripciones else {}

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
            instante = self._reloj.ahora()
            return self._cambiar(actual, lambda s: s.pagada(pago, instante))

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
            if suscripcion.id_firma is None:
                # La firma se guarda antes de emitir: si la emisión o la escritura fallan, el
                # reintento reutiliza esta firma en vez de pedir otra al proveedor.
                firma = self._firma.firmar(suscripcion)
                id_decision_firma = self._auditoria.registrar_firma(suscripcion, firma)
                suscripcion = self._cambiar(
                    suscripcion, lambda s: s.firmada(firma, id_decision_firma)
                )
                if suscripcion.estado is EstadoSuscripcion.EMITIDA:
                    return suscripcion, False
            poliza = self._emision.emitir(suscripcion)
            instante = self._reloj.ahora()
            emitida = self._cambiar(suscripcion, lambda s: s.emitida(poliza, instante))
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
        instante = self._reloj.ahora()
        return self._cambiar(suscripcion, lambda s: s.pagada(pago, instante))
