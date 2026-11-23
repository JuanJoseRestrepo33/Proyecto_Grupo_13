"""Casos de uso de la verificación de identidad: solicitar, consultar y estado de la cuenta."""

from __future__ import annotations

import threading
import uuid
from collections.abc import Callable
from dataclasses import replace

from app.compartido.errores import RecursoNoEncontrado
from app.compartido.reloj import Reloj

from ..dominio.errores import (
    IdentidadYaVerificadaError,
    ProveedorKYCNoDisponibleError,
    ReintentoNoPermitidoError,
    ReintentoSinCambiosError,
    VerificacionEnCursoError,
)
from ..dominio.modelo import (
    DatosDeIdentidad,
    EstadoIdentidad,
    EstadoVerificacion,
    MotivoRechazo,
    VerificacionIdentidad,
)
from ..dominio.puertos import ProveedorKYC, RegistroDeAuditoria, RepositorioVerificaciones

SUCESO_SOLICITADA = "solicitada"
SUCESO_RESUELTA = "resuelta"
SUCESO_PROVEEDOR_CAIDO = "proveedor_no_disponible"


class ServicioDeVerificacionDeIdentidad:
    def __init__(
        self,
        repositorio: RepositorioVerificaciones,
        proveedor: ProveedorKYC,
        auditoria: RegistroDeAuditoria,
        reloj: Reloj,
        datos_de_identidad: Callable[[str], DatosDeIdentidad],
    ) -> None:
        self._repositorio = repositorio
        self._proveedor = proveedor
        self._auditoria = auditoria
        self._reloj = reloj
        self._datos_de_identidad = datos_de_identidad
        # Serializa la comprobación de la última verificación con la reserva de la nueva.
        self._cerrojo = threading.Lock()

    def solicitar(self, id_cliente: str, canal: str) -> VerificacionIdentidad:
        """Envía los datos de la cuenta al proveedor y deja el intento en auditoría.

        La verificación se reserva `en_proceso` antes de llamar al proveedor: si el BFF se
        cansa de esperar y el usuario reintenta, ese reintento recibe `verificacion_en_curso`
        en lugar de abrir una segunda verificación.

        Si el proveedor no está disponible, el intento queda auditado como tal, la reserva se
        retira y el error tipado sube a la fachada (503). La cola para reintentar sin que el
        usuario espere (MOB-F01, criterio 8) está fuera del Sprint 1; la app ofrece reintentar.
        """
        datos = self._datos_de_identidad(id_cliente)
        ahora = self._reloj.ahora()
        verificacion = VerificacionIdentidad(
            id_verificacion=f"KYC-{uuid.uuid4()}",
            id_cliente=id_cliente,
            datos=datos,
            canal=canal,
            solicitada_en=ahora,
            actualizada_en=ahora,
            estado=EstadoVerificacion.EN_PROCESO,
            proveedor="",
        )
        with self._cerrojo:
            self._exigir_que_admita_otra(self._repositorio.ultima_del_cliente(id_cliente), datos)
            self._repositorio.guardar(verificacion)

        try:
            resultado = self._proveedor.verificar(datos)
        except ProveedorKYCNoDisponibleError as error:
            # El intento fallido también se audita (criterio 9), pero no queda como
            # verificación del cliente: así puede volver a intentarlo sin un 409.
            self._repositorio.quitar(verificacion.id_verificacion)
            proveedor = str(error.detalles.get("proveedor", ""))
            self._auditoria.registrar(
                replace(verificacion, proveedor=proveedor), SUCESO_PROVEEDOR_CAIDO
            )
            raise
        except Exception:
            self._repositorio.quitar(verificacion.id_verificacion)
            raise

        verificacion = verificacion.con_resultado(resultado, self._reloj.ahora())
        self._repositorio.guardar(verificacion)
        self._auditoria.registrar(verificacion, SUCESO_SOLICITADA)
        return verificacion

    @staticmethod
    def _exigir_que_admita_otra(
        ultima: VerificacionIdentidad | None, datos: DatosDeIdentidad
    ) -> None:
        if ultima is None:
            return
        if ultima.estado is EstadoVerificacion.APROBADA:
            raise IdentidadYaVerificadaError(
                "La identidad ya está verificada", id_verificacion=ultima.id_verificacion
            )
        if ultima.en_curso:
            raise VerificacionEnCursoError(
                "Ya hay una verificación en curso",
                id_verificacion=ultima.id_verificacion,
                estado=str(ultima.estado),
            )
        if ultima.reintento_permitido is not True:
            raise ReintentoNoPermitidoError(
                "El último rechazo no admite reintento", id_verificacion=ultima.id_verificacion
            )
        if ultima.motivo is MotivoRechazo.DATOS_NO_COINCIDEN and ultima.datos == datos:
            raise ReintentoSinCambiosError(
                "Los datos son los mismos del rechazo", id_verificacion=ultima.id_verificacion
            )

    def consultar(self, id_cliente: str) -> VerificacionIdentidad:
        """La última verificación del cliente. Si estaba `en_proceso`, pregunta al proveedor."""
        verificacion = self._repositorio.ultima_del_cliente(id_cliente)
        if verificacion is None:
            raise RecursoNoEncontrado(
                "El cliente no ha solicitado verificación de identidad", id_cliente=id_cliente
            )
        if verificacion.estado is not EstadoVerificacion.EN_PROCESO:
            return verificacion
        if verificacion.referencia_proveedor is None:
            return verificacion

        resultado = self._proveedor.consultar(verificacion.referencia_proveedor)
        if resultado.estado is EstadoVerificacion.EN_PROCESO:
            return verificacion
        verificacion = verificacion.con_resultado(resultado, self._reloj.ahora())
        self._repositorio.guardar(verificacion)
        self._auditoria.registrar(verificacion, SUCESO_RESUELTA)
        return verificacion

    def estado_identidad(self, id_cliente: str) -> EstadoIdentidad:
        """Lo que el BFF pone en `/sesion` y lo que otros módulos preguntan antes de dejar
        entrar a una función que exige identidad verificada (MOB-F01, criterio 1)."""
        ultima = self._repositorio.ultima_del_cliente(id_cliente)
        if ultima is None:
            return EstadoIdentidad.PENDIENTE_VERIFICACION
        if ultima.estado is EstadoVerificacion.EN_PROCESO:
            ultima = self.consultar(id_cliente)
        return ultima.estado_identidad
