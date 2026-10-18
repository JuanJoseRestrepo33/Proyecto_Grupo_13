"""Composición del módulo de identidad.

El proveedor de KYC/AML es el simulado; `IDENTIDAD_KYC_CAIDO=1` lo deja fuera de servicio
para ensayar el camino de falla sin tocar código. El proveedor real entra aquí, detrás del
mismo puerto, cuando exista.
"""

from __future__ import annotations

import os

from app.compartido.modulo import EstadoDeDependencia
from app.compartido.reloj import Reloj, RelojDelSistema
from app.compartido.unica import instancia_unica
from app.modulos import auditoria

from ..aplicacion.servicio import ServicioDeVerificacionDeIdentidad
from ..infraestructura.auditoria_nucleo import RegistroEnAuditoria
from ..infraestructura.proveedor_kyc_simulado import NOMBRE, ProveedorKYCSimulado
from ..infraestructura.repositorio_memoria import RepositorioEnMemoria

VARIABLE_PROVEEDOR_CAIDO = "IDENTIDAD_KYC_CAIDO"


def _proveedor_caido() -> bool:
    return os.environ.get(VARIABLE_PROVEEDOR_CAIDO, "") not in ("", "0", "false")


@instancia_unica
def _componentes() -> tuple[ServicioDeVerificacionDeIdentidad, Reloj, ProveedorKYCSimulado]:
    reloj = RelojDelSistema()
    proveedor = ProveedorKYCSimulado(caido=_proveedor_caido())
    servicio = ServicioDeVerificacionDeIdentidad(
        repositorio=RepositorioEnMemoria(),
        proveedor=proveedor,
        auditoria=RegistroEnAuditoria(auditoria.obtener_servicio()),
        reloj=reloj,
    )
    return servicio, reloj, proveedor


def obtener_servicio() -> ServicioDeVerificacionDeIdentidad:
    return _componentes()[0]


def obtener_reloj() -> Reloj:
    return _componentes()[1]


def reiniciar() -> None:
    """Descarta la composición actual. Solo para pruebas."""
    _componentes.cache_clear()


def estado_dependencias() -> tuple[EstadoDeDependencia, ...]:
    proveedor = _componentes()[2]
    if proveedor.caido:
        estado_kyc = EstadoDeDependencia("proveedor_kyc", "caido", f"{NOMBRE} fuera de servicio")
    else:
        estado_kyc = EstadoDeDependencia(
            "proveedor_kyc", "simulado", f"{NOMBRE}; el proveedor real llega con ARQ-02"
        )
    return (
        estado_kyc,
        EstadoDeDependencia(
            "repositorio_verificaciones",
            "simulado",
            "En memoria; persistencia por contexto pendiente",
        ),
        EstadoDeDependencia("auditoria", "ok", "Módulo de auditoría del núcleo (ARQ-03)"),
    )
