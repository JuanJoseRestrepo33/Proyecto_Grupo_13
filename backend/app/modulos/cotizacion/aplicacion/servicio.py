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
    BasePerfilamiento,
    Cotizacion,
    DatosAsegurado,
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
    producto: ProductoId
    asegurado: DatosAsegurado
    id_cliente: str | None = None


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
        )
        return self._crear(solicitud, id_origen=anterior.id_cotizacion)

    # -- interno -----------------------------------------------------------

    def _crear(self, solicitud: SolicitudDeCotizacion, id_origen: str | None) -> Cotizacion:
        ahora = self._reloj.ahora()
        producto = catalogo.obtener(solicitud.producto)
        base, id_consentimiento, perfil = self._resolver_base(solicitud.id_cliente)

        resultado = tarificador.tarificar(producto, solicitud.asegurado, base, perfil)

        cotizacion = Cotizacion(
            id_cotizacion=self._repositorio.siguiente_id(ahora.year),
            producto=producto.id,
            nombre_producto=producto.nombre,
            asegurado=solicitud.asegurado,
            oferta=resultado.oferta,
            creada_en=ahora,
            id_cliente=solicitud.id_cliente,
            id_consentimiento=id_consentimiento,
            perfil=perfil,
            id_cotizacion_origen=id_origen,
        )
        self._repositorio.guardar(cotizacion)

        self._auditoria.registrar(
            id_cotizacion=cotizacion.id_cotizacion,
            id_consentimiento=id_consentimiento,
            version_regla=resultado.oferta.version_regla,
            variables_entrada=resultado.variables_entrada,
            resultado={
                "prima_mensual": resultado.oferta.prima_mensual.monto,
                "prima_estandar": resultado.oferta.prima_estandar.monto,
                "ahorro_mensual": resultado.oferta.ahorro_mensual.monto,
                "base_perfilamiento": str(base),
            },
            ocurrido_en=ahora,
        )
        return cotizacion

    def _resolver_base(
        self, id_cliente: str | None
    ) -> tuple[BasePerfilamiento, str | None, PerfilDeRiesgo | None]:
        """Decide con qué información se tarifa.

        Un cliente anónimo cotiza a precio estándar: sin identidad no hay consentimiento
        que verificar, y sin consentimiento verificado no se tocan datos financieros.
        """
        if id_cliente is None:
            return BasePerfilamiento.ESTANDAR, None, None

        estado = self._consentimiento.consentimiento_vigente(id_cliente, FINALIDAD)
        if not estado.vigente or estado.id_consentimiento is None:
            return BasePerfilamiento.ESTANDAR, None, None

        respuesta = self._perfilamiento.perfil_de(id_cliente, estado.id_consentimiento)
        if respuesta.perfil is None:
            # El proveedor falló y no había nada en caché: se cotiza a precio estándar
            # en vez de dejar al cliente sin oferta (RC-03).
            return BasePerfilamiento.ESTANDAR, None, None

        base = (
            BasePerfilamiento.OPEN_FINANCE_EN_CACHE
            if respuesta.degradado
            else BasePerfilamiento.OPEN_FINANCE
        )
        return base, estado.id_consentimiento, respuesta.perfil
