"""Casos de uso de cotización.

Esta capa orquesta: decide si hay consentimiento, pide el perfil cuando corresponde,
llama al tarificador, persiste y registra la decisión. No calcula precios ni habla HTTP.

El orden importa y es una decisión de seguridad: **primero se verifica el consentimiento,
y solo entonces se consulta el perfil**. Si se hiciera al revés, una revocación seguiría
produciendo una consulta de datos financieros, que es justo lo que RC-04 prohíbe.
"""

from __future__ import annotations

import hashlib
import json
import threading
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
    ConsultaDeDecisiones,
    ConsultaPerfilDeRiesgo,
    EstadoConsentimiento,
    RegistroDeDecisiones,
    RepositorioCotizaciones,
)

#: Finalidad declarada al cliente al pedirle el consentimiento. Tiene que coincidir con
#: la que registra WEB-F02: un consentimiento vale para la finalidad que autoriza.
FINALIDAD = "cotizacion"

#: Cerrojos que serializan solicitudes con la misma huella. Son un número fijo, repartidos
#: por huella, para que la memoria no crezca con cada solicitud distinta.
_CERROJOS = 64


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


def huella_de_solicitud(solicitud: SolicitudDeCotizacion, id_consentimiento: str | None) -> str:
    """Qué hace que dos solicitudes sean "la misma" (WEB-F01, criterio 4).

    Entra el consentimiento con que se tarifaría: si el cliente lo revoca o lo otorga entre
    una solicitud y otra, ya no es la misma y la siguiente cotización refleja el cambio
    (RC-04). El canal no entra: la misma solicitud desde la web o desde la app es la misma.
    """
    datos = {
        "id_cliente": solicitud.id_cliente,
        "producto": str(solicitud.producto),
        "edad": solicitud.asegurado.edad,
        "ciudad": solicitud.asegurado.ciudad.strip().casefold(),
        "valor_asegurado": solicitud.asegurado.valor_asegurado,
        "usar_open_finance": solicitud.usar_open_finance,
        "id_consentimiento": id_consentimiento,
    }
    canonico = json.dumps(datos, sort_keys=True, ensure_ascii=False)
    return hashlib.sha256(canonico.encode()).hexdigest()


@dataclass(frozen=True, slots=True)
class Recalculo:
    """La prima de una cotización, recalculada con los datos de su decisión auditada."""

    id_cotizacion: str
    id_decision: str
    version_regla: str | None
    prima_emitida: int
    prima_recalculada: int

    @property
    def coincide(self) -> bool:
        return self.prima_emitida == self.prima_recalculada


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
        decisiones: ConsultaDeDecisiones | None = None,
    ) -> None:
        self._repositorio = repositorio
        self._consentimiento = consentimiento
        self._perfilamiento = perfilamiento
        self._auditoria = auditoria
        self._reloj = reloj
        self._decisiones = decisiones
        self._cerrojos = tuple(threading.Lock() for _ in range(_CERROJOS))

    # -- casos de uso ------------------------------------------------------

    def cotizar(self, solicitud: SolicitudDeCotizacion) -> Cotizacion:
        """Crea una cotización nueva con su oferta.

        Para un cliente identificado es idempotente: la misma solicitud, mientras su oferta
        siga vigente, devuelve la misma cotización sin tarifar ni auditar otra vez. Un doble
        clic o un reintento por la red no deja dos ofertas distintas.
        """
        consentimiento = self._consultar_consentimiento(solicitud)
        if solicitud.id_cliente is None:
            return self._crear(solicitud, consentimiento, id_origen=None)

        id_consentimiento = consentimiento.id_consentimiento if consentimiento else None
        huella = huella_de_solicitud(solicitud, id_consentimiento)
        with self._cerrojos[int(huella[:8], 16) % _CERROJOS]:
            existente = self._repositorio.vigente_por_huella(
                solicitud.id_cliente, huella, self._reloj.ahora()
            )
            if existente is not None:
                return existente
            return self._crear(solicitud, consentimiento, id_origen=None, huella=huella)

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
        consentimiento = self._consultar_consentimiento(solicitud)
        return self._crear(solicitud, consentimiento, id_origen=anterior.id_cotizacion)

    def recalcular(self, id_cotizacion: str) -> Recalculo:
        """Reconstruye la prima con la versión de regla y las variables auditadas (RC-08).

        Lee la decisión del log de auditoría, no de la cotización: es la prueba de que lo
        registrado basta para explicar el precio, aunque la cotización viva en otra base.
        """
        cotizacion = self.obtener(id_cotizacion)
        decision = None
        if self._decisiones is not None and cotizacion.id_decision is not None:
            decision = self._decisiones.decision(cotizacion.id_decision)
        if decision is None:
            raise RecursoNoEncontrado(
                f"la decisión de la cotización {id_cotizacion} no está en el log",
                id_cotizacion=id_cotizacion,
                id_decision=cotizacion.id_decision,
            )
        return Recalculo(
            id_cotizacion=id_cotizacion,
            id_decision=decision.id_decision,
            version_regla=decision.version_regla,
            prima_emitida=cotizacion.oferta.prima_mensual.monto,
            prima_recalculada=tarificador.recalcular_prima(decision.variables_entrada).monto,
        )

    # -- interno -----------------------------------------------------------

    def _crear(
        self,
        solicitud: SolicitudDeCotizacion,
        consentimiento: EstadoConsentimiento | None,
        id_origen: str | None,
        huella: str | None = None,
    ) -> Cotizacion:
        ahora = self._reloj.ahora()
        producto = catalogo.obtener(solicitud.producto)
        resuelta = self._resolver_base(solicitud, consentimiento)

        resultado = tarificador.tarificar(
            producto, solicitud.asegurado, resuelta.base, resuelta.perfil
        )

        # La decisión se registra antes de guardar para que la cotización lleve su
        # referencia. El log escribe en segundo plano, así que no demora la respuesta.
        id_cotizacion = self._repositorio.siguiente_id(ahora.year)
        id_decision = self._auditoria.registrar(
            id_cotizacion=id_cotizacion,
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

        cotizacion = Cotizacion(
            id_cotizacion=id_cotizacion,
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
            huella=huella,
            id_decision=id_decision,
        )
        self._repositorio.guardar(cotizacion)
        return cotizacion

    def _consultar_consentimiento(
        self, solicitud: SolicitudDeCotizacion
    ) -> EstadoConsentimiento | None:
        """El consentimiento del cliente, o None si no corresponde preguntar.

        No se pregunta por un cliente anónimo ni por uno que pidió cotizar sin sus datos.
        """
        if not solicitud.usar_open_finance or solicitud.id_cliente is None:
            return None
        return self._consentimiento.consentimiento_vigente(solicitud.id_cliente, FINALIDAD)

    def _resolver_base(
        self, solicitud: SolicitudDeCotizacion, consentimiento: EstadoConsentimiento | None
    ) -> _Base:
        """Decide con qué información se tarifa.

        Un cliente anónimo cotiza a precio estándar: sin identidad no hay consentimiento
        que verificar, y sin consentimiento verificado no se tocan datos financieros. Si el
        cliente pidió no usar sus datos, tampoco se consultó su consentimiento.
        """
        if not solicitud.usar_open_finance:
            return _Base(BasePerfilamiento.ESTANDAR, motivo=MotivoTarifa.OPEN_FINANCE_DESACTIVADO)
        if (
            solicitud.id_cliente is None
            or consentimiento is None
            or not consentimiento.vigente
            or consentimiento.id_consentimiento is None
        ):
            return _Base(BasePerfilamiento.ESTANDAR, motivo=MotivoTarifa.SIN_CONSENTIMIENTO)

        respuesta = self._perfilamiento.perfil_de(
            solicitud.id_cliente, consentimiento.id_consentimiento
        )
        if respuesta.perfil is None:
            # El proveedor falló y no había nada en caché: se cotiza a precio estándar
            # en vez de dejar al cliente sin oferta (RC-03).
            return _Base(BasePerfilamiento.ESTANDAR, motivo=MotivoTarifa.PROVEEDOR_NO_DISPONIBLE)

        base = (
            BasePerfilamiento.OPEN_FINANCE_EN_CACHE
            if respuesta.degradado
            else BasePerfilamiento.OPEN_FINANCE
        )
        return _Base(base, consentimiento.id_consentimiento, respuesta.perfil)
