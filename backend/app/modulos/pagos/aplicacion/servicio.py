"""Casos de uso de pagos: cobrar sin cobro doble y consultar un pago (WEB-F03, criterio 2).

El módulo no sabe qué se cobra: recibe un `concepto` (el id de la suscripción) y su monto.
Quien decide si el concepto se puede pagar es suscripción; aquí solo se garantiza que una
clave es un único cobro y que un concepto no se cobra dos veces.
"""

from __future__ import annotations

import uuid
from collections.abc import Sequence
from dataclasses import replace
from datetime import timedelta

from app.compartido.dinero import Dinero
from app.compartido.errores import DependenciaNoDisponible
from app.compartido.reloj import Reloj

from ..dominio.errores import (
    ClaveDeOtroPagoError,
    PagoEnCursoError,
    PagoNoEncontradoError,
    PagoYaRegistradoError,
)
from ..dominio.modelo import EstadoPago, MedioPago, Pago
from ..dominio.puertos import PasarelaDePagos, RegistroDeAuditoria, RepositorioPagos

#: Tiempo tras el cual un pago que sigue en `procesando` se concilia con la pasarela. Debe
#: superar el tiempo máximo de espera de una llamada a la pasarela: antes de eso, el intento
#: original todavía puede estar esperando su respuesta.
ESPERA_ANTES_DE_CONCILIAR = timedelta(seconds=60)


class ServicioDePagos:
    def __init__(
        self,
        repositorio: RepositorioPagos,
        pasarela: PasarelaDePagos,
        auditoria: RegistroDeAuditoria,
        reloj: Reloj,
        espera_antes_de_conciliar: timedelta = ESPERA_ANTES_DE_CONCILIAR,
    ) -> None:
        self._repositorio = repositorio
        self._pasarela = pasarela
        self._auditoria = auditoria
        self._reloj = reloj
        self._espera = espera_antes_de_conciliar

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

        Un pago que lleva más de `espera_antes_de_conciliar` en `procesando` (la pasarela
        respondió pero no se pudo guardar el resultado, o el proceso murió a mitad) se
        concilia con la pasarela antes de decidir: si cobró, se guarda ese resultado; si
        nunca cobró, la reserva se libera y se puede intentar de nuevo.
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
        # Dos vueltas: la segunda solo ocurre si la conciliación liberó la reserva que
        # bloqueaba el intento.
        for _ in range(2):
            reservado = self._repositorio.reservar(candidato)
            if reservado.id_pago == candidato.id_pago:
                return self._cobrar_reservado(reservado), True
            self._exigir_misma_operacion(reservado, candidato)
            if not self._colgado(reservado):
                return self._repetido(reservado, candidato), False
            conciliado = self._conciliar(reservado)
            if conciliado is not None and (
                conciliado.activo or conciliado.clave_idempotencia == clave_idempotencia
            ):
                return self._repetido(conciliado, candidato), False
        raise PagoEnCursoError("El pago sigue en curso", concepto=concepto)

    def _cobrar_reservado(self, reservado: Pago) -> Pago:
        try:
            respuesta = self._pasarela.cobrar(reservado.id_pago, reservado.monto, reservado.medio)
        except DependenciaNoDisponible:
            # El puerto garantiza que en este caso no hubo cobro: se libera para reintentar.
            self._repositorio.liberar(reservado.id_pago)
            raise
        # Cualquier otro error deja el pago en `procesando`: no se sabe si la pasarela cobró,
        # y liberarlo permitiría un segundo cobro con la misma clave. Se resuelve conciliando.
        pago = reservado.resuelto(respuesta.estado, respuesta.referencia, self._reloj.ahora())
        return self._guardar_y_auditar(pago)

    @staticmethod
    def _exigir_misma_operacion(existente: Pago, candidato: Pago) -> None:
        if existente.clave_idempotencia == candidato.clave_idempotencia and (
            existente.concepto != candidato.concepto or existente.id_cliente != candidato.id_cliente
        ):
            raise ClaveDeOtroPagoError("La clave de idempotencia pertenece a otro pago")

    def _colgado(self, pago: Pago) -> bool:
        return (
            pago.estado is EstadoPago.PROCESANDO
            and self._reloj.ahora() - pago.creado_en >= self._espera
        )

    def _conciliar(self, pago: Pago) -> Pago | None:
        """Resuelve un pago colgado con lo que diga la pasarela. None si nunca se cobró: en
        ese caso la reserva se libera."""
        respuesta = self._pasarela.consultar_pago(pago.id_pago)
        if respuesta is None:
            self._repositorio.liberar(pago.id_pago)
            return None
        resuelto = pago.resuelto(respuesta.estado, respuesta.referencia, self._reloj.ahora())
        return self._guardar_y_auditar(resuelto)

    def _repetido(self, existente: Pago, candidato: Pago) -> Pago:
        if existente.clave_idempotencia != candidato.clave_idempotencia:
            raise PagoYaRegistradoError(
                "El concepto ya tiene un pago", id_pago=existente.id_pago, estado=existente.estado
            )
        if existente.estado is EstadoPago.PROCESANDO:
            raise PagoEnCursoError("El pago sigue en curso", id_pago=existente.id_pago)
        return existente

    def leer(self, id_cliente: str, concepto: str, id_pago: str) -> Pago:
        """El pago tal como está guardado, sin consultar la pasarela. Un pago de otro cliente
        responde igual que uno inexistente."""
        pago = self._repositorio.obtener(id_pago)
        if pago is None or pago.concepto != concepto or pago.id_cliente != id_cliente:
            raise PagoNoEncontradoError("El pago no existe", id_pago=id_pago)
        return pago

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
        return self._repositorio.ultimos((concepto,)).get(concepto)

    def ultimos(self, conceptos: Sequence[str]) -> dict[str, Pago]:
        """El intento más reciente de cada concepto, en una sola consulta."""
        return self._repositorio.ultimos(conceptos) if conceptos else {}

    def _guardar_y_auditar(self, pago: Pago) -> Pago:
        pago = replace(pago, id_decision=self._auditoria.registrar(pago))
        self._repositorio.actualizar(pago)
        return pago
