"""Casos de uso de pólizas: consultar y emitir.

Otros módulos preguntan aquí, nunca leyendo los datos de pólizas directamente (ARQ-01). El
reporte de siniestros valida la vigencia con `poliza_del_cliente`; suscripción emite la
póliza con `emitir` cuando la oferta está pagada y firmada (WEB-F03, criterio 4).
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import replace
from datetime import date

from app.compartido.reloj import Reloj

from ..dominio.errores import DocumentoNoDisponibleError, PolizaNoEncontradaError
from ..dominio.modelo import PlantillaDePoliza, Poliza, SolicitudDeEmision, vigencia_anual
from ..dominio.puertos import (
    AlmacenDeDocumentos,
    GeneradorDeDocumento,
    RegistroDeAuditoria,
    RepositorioPolizas,
)


class ServicioDePolizas:
    def __init__(
        self,
        repositorio: RepositorioPolizas,
        reloj: Reloj,
        plantillas: Sequence[PlantillaDePoliza] = (),
        *,
        almacen: AlmacenDeDocumentos | None = None,
        generador: GeneradorDeDocumento | None = None,
        auditoria: RegistroDeAuditoria | None = None,
    ) -> None:
        self._repositorio = repositorio
        self._reloj = reloj
        self._plantillas = tuple(plantillas)
        self._almacen = almacen
        self._generador = generador
        self._auditoria = auditoria

    # -- consulta ------------------------------------------------------------------------

    def del_cliente(self, id_cliente: str) -> tuple[Poliza, ...]:
        """Las pólizas del cliente, de la más reciente a la más antigua."""
        propias = self._repositorio.del_cliente(id_cliente)
        de_plantilla = tuple(p.para(id_cliente) for p in self._plantillas)
        return tuple(sorted(propias + de_plantilla, key=lambda p: p.inicio, reverse=True))

    def poliza_del_cliente(self, numero: str, id_cliente: str) -> Poliza | None:
        """La póliza si existe y es del cliente; si no, None, sin distinguir los dos casos."""
        poliza = self._repositorio.por_numero(numero)
        if poliza is not None:
            return poliza if poliza.id_cliente == id_cliente else None
        return next((p for p in self.del_cliente(id_cliente) if p.numero == numero), None)

    def exigir_del_cliente(self, numero: str, id_cliente: str) -> Poliza:
        poliza = self.poliza_del_cliente(numero, id_cliente)
        if poliza is None:
            raise PolizaNoEncontradaError("La póliza no existe", numero=numero)
        return poliza

    def documento(self, numero: str, id_cliente: str) -> tuple[Poliza, bytes]:
        """El PDF de una póliza del cliente (WEB-F03, criterio 4)."""
        poliza = self.exigir_del_cliente(numero, id_cliente)
        contenido = None
        if poliza.id_documento is not None and self._almacen is not None:
            contenido = self._almacen.leer(poliza.id_documento)
        if contenido is None:
            raise DocumentoNoDisponibleError("La póliza no tiene documento", numero=numero)
        return poliza, contenido

    def hoy(self) -> date:
        """El día de hoy según el reloj del módulo, para calcular el estado de una póliza."""
        return self._reloj.ahora().date()

    # -- emisión (WEB-F03) ---------------------------------------------------------------

    def emitir(self, solicitud: SolicitudDeEmision) -> tuple[Poliza, bool]:
        """Emite la póliza anual de la suscripción, con su documento. Devuelve si es nueva.

        Es idempotente por suscripción: emitir otra vez devuelve la misma póliza, con el mismo
        número, sin otro documento ni otra entrada en la auditoría. Así un reintento de la
        firma, o un fallo justo después de emitir, nunca deja dos pólizas.
        """
        existente = self._repositorio.por_suscripcion(solicitud.id_suscripcion)
        if existente is not None:
            return existente, False
        if self._almacen is None or self._generador is None or self._auditoria is None:
            raise RuntimeError("El servicio de pólizas no está armado para emitir")

        ahora = self._reloj.ahora()
        inicio = ahora.date()
        poliza = Poliza(
            numero=self._repositorio.siguiente_numero(ahora.year),
            id_cliente=solicitud.id_cliente,
            producto=solicitud.producto,
            inicio=inicio,
            fin=vigencia_anual(inicio),
            nombre_producto=solicitud.nombre_producto,
            coberturas=solicitud.coberturas,
            deducible=solicitud.deducible,
            valor_asegurado=solicitud.valor_asegurado,
            prima_mensual=solicitud.prima_mensual,
            id_suscripcion=solicitud.id_suscripcion,
            id_firma=solicitud.id_firma,
            emitida_en=ahora,
            canal=solicitud.canal,
        )
        id_documento = self._almacen.guardar(poliza.numero, self._generador.generar(poliza))
        guardada = self._repositorio.emitir_si_nueva(replace(poliza, id_documento=id_documento))
        if guardada.numero != poliza.numero:
            # Otra solicitud emitió primero: esa es la póliza de la suscripción.
            return guardada, False
        auditada = replace(guardada, id_decision=self._auditoria.registrar_emision(guardada))
        self._repositorio.guardar(auditada)
        return auditada, True
