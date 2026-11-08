"""Casos de uso de pagos: cobrar sin cobro doble y consultar un pago (WEB-F03, criterio 2).

El módulo no sabe qué se cobra: recibe un `concepto` (el id de la suscripción) y su monto.
Quien decide si el concepto se puede pagar es suscripción; aquí solo se garantiza que una
clave es un único cobro y que un concepto no se cobra dos veces.
"""

from __future__ import annotations

import uuid
from dataclasses import replace

from app.compartido.dinero import Dinero
from app.compartido.reloj import Reloj

from ..dominio.errores import (
    ClaveDeOtroPagoError,
    PagoEnCursoError,
    PagoNoEncontradoError,
    PagoYaRegistradoError,
)
from ..dominio.modelo import EstadoPago, MedioPago, Pago
from ..dominio.puertos import PasarelaDePagos, RegistroDeAuditoria, RepositorioPagos


class ServicioDePagos:
    def __init__(
        self,
        repositorio: RepositorioPagos,
        pasarela: PasarelaDePagos,
        auditoria: RegistroDeAuditoria,
        reloj: Reloj,
    ) -> None:
        self._repositorio = repositorio
        self._pasarela = pasarela
        self._auditoria = auditoria
        self._reloj = reloj

    def cobrar(
        self,
        concepto: str,
        id_cliente: str,
        monto: Dinero,
        medio: MedioPago,
        clave_idempotencia: str,
        canal: str,
    ) -> tuple[Pago, bool]:
        """Cobra el concepto una sola vez. Devuelve el pago y si es un intento nuevo.

        - Misma clave, intento terminado: el mismo pago, sin otro cobro (`nuevo` en False).
        - Misma clave, intento sin respuesta todavía: `pago_en_curso`.
        - Otra clave con un pago activo del concepto: `pago_ya_registrado`.
        """
        candidato = Pago(
            id_pago=f"PAG-{uuid.uuid4()}",
            concepto=concepto,
            id_cliente=id_cliente,
            medio=medio,
            monto=monto,
            clave_idempotencia=clave_idempotencia,
            canal=canal,
            creado_en=self._reloj.ahora(),
        )
        reservado = self._repositorio.reservar(candidato)
        if reservado.id_pago != candidato.id_pago:
            return self._repetido(reservado, candidato), False

        try:
            respuesta = self._pasarela.cobrar(reservado.id_pago, monto, medio)
        except Exception:
            # La pasarela no respondió y no cobró: la reserva se libera para poder reintentar.
            self._repositorio.liberar(reservado.id_pago)
            raise
        pago = reservado.resuelto(respuesta.estado, respuesta.referencia, self._reloj.ahora())
        return self._guardar_y_auditar(pago), True

    def _repetido(self, existente: Pago, candidato: Pago) -> Pago:
        if existente.clave_idempotencia != candidato.clave_idempotencia:
            raise PagoYaRegistradoError(
                "El concepto ya tiene un pago", id_pago=existente.id_pago, estado=existente.estado
            )
        if existente.concepto != candidato.concepto or existente.id_cliente != candidato.id_cliente:
            raise ClaveDeOtroPagoError("La clave de idempotencia pertenece a otro pago")
        if existente.estado is EstadoPago.PROCESANDO:
            raise PagoEnCursoError("El pago sigue en curso", id_pago=existente.id_pago)
        return existente

    def consultar(self, concepto: str, id_pago: str) -> Pago:
        """El pago con su estado actual. Un pago pendiente se resuelve con la pasarela."""
        pago = self._repositorio.obtener(id_pago)
        if pago is None or pago.concepto != concepto:
            raise PagoNoEncontradoError("El pago no existe", id_pago=id_pago)
        if pago.estado is not EstadoPago.PENDIENTE or pago.referencia is None:
            return pago
        respuesta = self._pasarela.consultar(pago.referencia)
        if respuesta.estado is EstadoPago.PENDIENTE:
            return pago
        resuelto = pago.resuelto(respuesta.estado, respuesta.referencia, self._reloj.ahora())
        return self._guardar_y_auditar(resuelto)

    def ultimo(self, concepto: str) -> Pago | None:
        """El intento más reciente del concepto, o None si nunca se intentó pagar."""
        pagos = self._repositorio.del_concepto(concepto)
        return pagos[-1] if pagos else None

    def _guardar_y_auditar(self, pago: Pago) -> Pago:
        pago = replace(pago, id_decision=self._auditoria.registrar(pago))
        self._repositorio.actualizar(pago)
        return pago
