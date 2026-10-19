"""Casos de uso de la verificación de identidad: solicitar, consultar y estado de la cuenta."""

from __future__ import annotations

import uuid
from dataclasses import replace

from app.compartido.errores import RecursoNoEncontrado
from app.compartido.reloj import Reloj

from ..dominio.errores import (
    IdentidadYaVerificadaError,
    ProveedorKYCNoDisponibleError,
    VerificacionEnCursoError,
)
from ..dominio.modelo import (
    DatosDeIdentidad,
    EstadoIdentidad,
    EstadoVerificacion,
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
    ) -> None:
        self._repositorio = repositorio
        self._proveedor = proveedor
        self._auditoria = auditoria
        self._reloj = reloj

    def solicitar(
        self, id_cliente: str, datos: DatosDeIdentidad, canal: str
    ) -> VerificacionIdentidad:
        """Envía los datos al proveedor y deja el intento en auditoría, pase lo que pase.

        Si el proveedor no está disponible, el intento queda auditado como tal y el error
        tipado sube a la fachada (503). La cola para reintentar sin que el usuario espere
        (MOB-F01, criterio 8) está fuera del Sprint 1; la app ofrece reintentar.
        """
        ultima = self._repositorio.ultima_del_cliente(id_cliente)
        if ultima is not None and ultima.estado is EstadoVerificacion.APROBADA:
            raise IdentidadYaVerificadaError(
                "La identidad ya está verificada", id_verificacion=ultima.id_verificacion
            )
        if ultima is not None and ultima.en_curso:
            raise VerificacionEnCursoError(
                "Ya hay una verificación en curso",
                id_verificacion=ultima.id_verificacion,
                estado=str(ultima.estado),
            )

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
        try:
            resultado = self._proveedor.verificar(datos)
        except ProveedorKYCNoDisponibleError as error:
            # El intento fallido también se audita (criterio 9), pero no se guarda como
            # verificación del cliente: así puede volver a intentarlo sin un 409.
            proveedor = str(error.detalles.get("proveedor", ""))
            self._auditoria.registrar(
                replace(verificacion, proveedor=proveedor), SUCESO_PROVEEDOR_CAIDO
            )
            raise

        verificacion = verificacion.con_resultado(resultado, self._reloj.ahora())
        self._repositorio.guardar(verificacion)
        self._auditoria.registrar(verificacion, SUCESO_SOLICITADA)
        return verificacion

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
