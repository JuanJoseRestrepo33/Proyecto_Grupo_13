"""Adaptadores simulados de los módulos vecinos.

Existen para que WEB-F01 sea ejecutable y probable hoy, sin esperar a WEB-F02 (servicio
de consentimiento), ARQ-02 (adaptador de Open Finance con circuit breaker) ni ARQ-03
(log de auditoría en PostgreSQL).

Cada uno implementa su puerto y nada más. Cuando el módulo real exista, se cambia la
línea de inyección del arranque y estos quedan solo para las pruebas.
"""

from __future__ import annotations

from datetime import datetime

from app.modulos.cotizacion.dominio.modelo import PerfilDeRiesgo
from app.modulos.cotizacion.dominio.puertos import EstadoConsentimiento, RespuestaPerfil


class ConsentimientoSimulado:
    """Consentimiento vigente para los clientes que se le declaren.

    Por omisión no hay ninguno: un cliente desconocido cotiza a precio estándar, que es
    el comportamiento seguro.
    """

    def __init__(self, vigentes: dict[str, str] | None = None) -> None:
        self._vigentes = dict(vigentes or {})

    def otorgar(self, id_cliente: str, id_consentimiento: str) -> None:
        self._vigentes[id_cliente] = id_consentimiento

    def revocar(self, id_cliente: str) -> None:
        self._vigentes.pop(id_cliente, None)

    def consentimiento_vigente(self, id_cliente: str, finalidad: str) -> EstadoConsentimiento:
        del finalidad  # el simulado no distingue finalidades; el módulo real sí
        id_consentimiento = self._vigentes.get(id_cliente)
        if id_consentimiento is None:
            return EstadoConsentimiento(vigente=False)
        return EstadoConsentimiento(vigente=True, id_consentimiento=id_consentimiento)


class PerfilamientoSimulado:
    """Perfil determinista, apto para usarse como aserción en una prueba.

    `degradado` imita el camino de RC-03: el proveedor no respondió a tiempo y el perfil
    salió de la caché. `sin_perfil` imita que además la caché estaba vacía.
    """

    def __init__(self, degradado: bool = False, sin_perfil: bool = False) -> None:
        self.degradado = degradado
        self.sin_perfil = sin_perfil

    def perfil_de(self, id_cliente: str, id_consentimiento: str) -> RespuestaPerfil:
        del id_consentimiento
        if self.sin_perfil:
            return RespuestaPerfil(perfil=None, degradado=True)
        perfil = PerfilDeRiesgo(
            puntaje=720 if id_cliente else 600,
            comportamiento_pago="al dia",
            nivel_endeudamiento="moderado",
            desde_cache=self.degradado,
        )
        return RespuestaPerfil(perfil=perfil, degradado=self.degradado)


class RegistroDeDecisionesEnMemoria:
    """Log append-only en memoria.

    Solo expone `registrar` y una lectura de solo lectura. No hay forma de modificar ni
    de borrar una entrada, igual que en la tabla real de ARQ-03.
    """

    def __init__(self) -> None:
        self._entradas: list[dict[str, object]] = []

    def registrar(
        self,
        *,
        id_cotizacion: str,
        id_consentimiento: str | None,
        id_cliente: str | None,
        version_regla: str,
        variables_entrada: dict[str, object],
        resultado: dict[str, object],
        ocurrido_en: datetime,
    ) -> str:
        id_decision = f"DEC-{len(self._entradas) + 1:06d}"
        self._entradas.append(
            {
                "id_decision": id_decision,
                "id_cotizacion": id_cotizacion,
                "id_consentimiento": id_consentimiento,
                "id_cliente": id_cliente,
                "version_regla": version_regla,
                "variables_entrada": variables_entrada,
                "resultado": resultado,
                "ocurrido_en": ocurrido_en.isoformat(),
            }
        )
        return id_decision

    @property
    def entradas(self) -> tuple[dict[str, object], ...]:
        return tuple(self._entradas)
