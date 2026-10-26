"""Casos de uso de cotización.

Esta capa orquesta: decide si hay consentimiento, pide el perfil cuando corresponde,
llama al tarificador, persiste y registra la decisión. No calcula precios ni habla HTTP.

El orden importa y es una decisión de seguridad: **primero se verifica el consentimiento,
y solo entonces se consulta el perfil**. Si se hiciera al revés, una revocación seguiría
produciendo una consulta de datos financieros, que es justo lo que RC-04 prohíbe.
"""

from __future__ import annotations

from dataclasses import dataclass

from app.compartido.errores import RecursoNoEncontrado
from app.compartido.reloj import Reloj
from app.modulos.cotizacion.dominio import catalogo, tarificador
from app.modulos.cotizacion.dominio.modelo import (
    CANAL_API,
    BasePerfilamiento,
    Cotizacion,
    DatosAsegurado,
    MotivoTarifa,
    PerfilDeRiesgo,
    ProductoId,
)
from app.modulos.cotizacion.dominio.puertos import (
    ConsultaConsentimiento,
    ConsultaPerfilDeRiesgo,
    RegistroDeDecisiones,
    RepositorioCotizaciones,
)

#: Finalidad declarada al cliente al pedirle el consentimiento. Tiene que coincidir con
#: la que registra WEB-F02: un consentimiento vale para la finalidad que autoriza.
FINALIDAD = "cotizacion"


@dataclass(frozen=True, slots=True)
class SolicitudDeCotizacion:
    """`usar_open_finance` en False es el cliente pidiendo cotizar sin sus datos financieros:
    ni siquiera se consulta el consentimiento. `canal` es el que origina la solicitud y queda
    en la decisión auditada."""

    producto: ProductoId
    asegurado: DatosAsegurado
    id_cliente: str | None = None
    usar_open_finance: bool = True
    canal: str = CANAL_API


@dataclass(frozen=True, slots=True)
class _Base:
    """Con qué información se tarifa, y por qué, si es la estándar."""

    base: BasePerfilamiento
    id_consentimiento: str | None = None
    perfil: PerfilDeRiesgo | None = None
    motivo: MotivoTarifa | None = None


class ServicioDeCotizacion:
    def __init__(
        self,
        repositorio: RepositorioCotizaciones,
        consentimiento: ConsultaConsentimiento,
        perfilamiento: ConsultaPerfilDeRiesgo,
        auditoria: RegistroDeDecisiones,
        reloj: Reloj,
    ) -> None:
        self._repositorio = repositorio
        self._consentimiento = consentimiento
        self._perfilamiento = perfilamiento
        self._auditoria = auditoria
        self._reloj = reloj

    # -- casos de uso ------------------------------------------------------

    def cotizar(self, solicitud: SolicitudDeCotizacion) -> Cotizacion:
        """Crea una cotización nueva con su oferta."""
        return self._crear(solicitud, id_origen=None)

    def obtener(self, id_cotizacion: str) -> Cotizacion:
        """Devuelve la cotización. No falla si venció: el estado lo dice el recurso."""
        cotizacion = self._repositorio.obtener(id_cotizacion)
        if cotizacion is None:
            raise RecursoNoEncontrado(
                f"no existe la cotización {id_cotizacion}", id_cotizacion=id_cotizacion
            )
        return cotizacion

    def recotizar(self, id_cotizacion: str) -> Cotizacion:
        """Vuelve a tarifar con los mismos datos y precios del momento.

        Genera una cotización **nueva**, con identificador propio, y deja la anterior
        intacta. Una oferta emitida no se reescribe: se reemplaza por otra que la
        referencia. Así el histórico sigue siendo reconstruible.
        """
        anterior = self.obtener(id_cotizacion)
        solicitud = SolicitudDeCotizacion(
            producto=anterior.producto,
            asegurado=anterior.asegurado,
            id_cliente=anterior.id_cliente,
            usar_open_finance=anterior.usar_open_finance,
            canal=anterior.canal,
        )
        return self._crear(solicitud, id_origen=anterior.id_cotizacion)

    # -- interno -----------------------------------------------------------

    def _crear(self, solicitud: SolicitudDeCotizacion, id_origen: str | None) -> Cotizacion:
        ahora = self._reloj.ahora()
        producto = catalogo.obtener(solicitud.producto)
        resuelta = self._resolver_base(solicitud)

        resultado = tarificador.tarificar(
            producto, solicitud.asegurado, resuelta.base, resuelta.perfil
        )

        cotizacion = Cotizacion(
            id_cotizacion=self._repositorio.siguiente_id(ahora.year),
            producto=producto.id,
            nombre_producto=producto.nombre,
            asegurado=solicitud.asegurado,
            oferta=resultado.oferta,
            creada_en=ahora,
            id_cliente=solicitud.id_cliente,
            id_consentimiento=resuelta.id_consentimiento,
            perfil=resuelta.perfil,
            id_cotizacion_origen=id_origen,
            motivo_tarifa=resuelta.motivo,
            usar_open_finance=solicitud.usar_open_finance,
            canal=solicitud.canal,
        )
        self._repositorio.guardar(cotizacion)

        self._auditoria.registrar(
            id_cotizacion=cotizacion.id_cotizacion,
            id_consentimiento=resuelta.id_consentimiento,
            id_cliente=solicitud.id_cliente,
            canal=solicitud.canal,
            version_regla=resultado.oferta.version_regla,
            variables_entrada=resultado.variables_entrada,
            resultado={
                "prima_mensual": resultado.oferta.prima_mensual.monto,
                "prima_estandar": resultado.oferta.prima_estandar.monto,
                "ahorro_mensual": resultado.oferta.ahorro_mensual.monto,
                "base_perfilamiento": str(resuelta.base),
                "motivo_tarifa": str(resuelta.motivo) if resuelta.motivo else None,
            },
            ocurrido_en=ahora,
        )
        return cotizacion

    def _resolver_base(self, solicitud: SolicitudDeCotizacion) -> _Base:
        """Decide con qué información se tarifa.

        Un cliente anónimo cotiza a precio estándar: sin identidad no hay consentimiento
        que verificar, y sin consentimiento verificado no se tocan datos financieros. Si el
        cliente pidió no usar sus datos, tampoco se consulta su consentimiento.
        """
        if not solicitud.usar_open_finance:
            return _Base(BasePerfilamiento.ESTANDAR, motivo=MotivoTarifa.OPEN_FINANCE_DESACTIVADO)
        if solicitud.id_cliente is None:
            return _Base(BasePerfilamiento.ESTANDAR, motivo=MotivoTarifa.SIN_CONSENTIMIENTO)

        estado = self._consentimiento.consentimiento_vigente(solicitud.id_cliente, FINALIDAD)
        if not estado.vigente or estado.id_consentimiento is None:
            return _Base(BasePerfilamiento.ESTANDAR, motivo=MotivoTarifa.SIN_CONSENTIMIENTO)

        respuesta = self._perfilamiento.perfil_de(solicitud.id_cliente, estado.id_consentimiento)
        if respuesta.perfil is None:
            # El proveedor falló y no había nada en caché: se cotiza a precio estándar
            # en vez de dejar al cliente sin oferta (RC-03).
            return _Base(BasePerfilamiento.ESTANDAR, motivo=MotivoTarifa.PROVEEDOR_NO_DISPONIBLE)

        base = (
            BasePerfilamiento.OPEN_FINANCE_EN_CACHE
            if respuesta.degradado
            else BasePerfilamiento.OPEN_FINANCE
        )
        return _Base(base, estado.id_consentimiento, respuesta.perfil)
