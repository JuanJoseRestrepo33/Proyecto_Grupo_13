"""Finalidades y entidades que el cliente puede autorizar.

Es el mismo catálogo que publica el BFF web (`contratos/bff-web/openapi.yaml`,
`apps/web/src/mocks/datos.ts`): mismos códigos, mismas vigencias y mismas versiones de
texto legal, para que el cliente web funcione igual contra el núcleo que contra sus mocks.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum

from .errores import EntidadesInvalidasError


class CodigoFinalidad(StrEnum):
    COTIZACION = "cotizacion"
    SUSCRIPCION = "suscripcion"
    PREVENCION_FRAUDE = "prevencion_fraude"


@dataclass(frozen=True, slots=True)
class Entidad:
    id: str
    nombre: str


@dataclass(frozen=True, slots=True)
class Finalidad:
    codigo: CodigoFinalidad
    nombre: str
    descripcion: str
    alcance: tuple[str, ...]
    vigencia_meses: int
    entidades_origen: tuple[Entidad, ...]
    version_texto: str


ENTIDADES: tuple[Entidad, ...] = (
    Entidad("ent-andes", "Banco Andes S.A."),
    Entidad("ent-sur", "Cooperativa Financiera del Sur"),
)

FINALIDADES: tuple[Finalidad, ...] = (
    Finalidad(
        codigo=CodigoFinalidad.COTIZACION,
        nombre="Cotización",
        descripcion="Calcular una prima personalizada con tu comportamiento financiero real.",
        alcance=("Saldos promedio", "Historial de pagos", "Ingresos recurrentes"),
        vigencia_meses=12,
        entidades_origen=ENTIDADES,
        version_texto="cotizacion-2026.1",
    ),
    Finalidad(
        codigo=CodigoFinalidad.SUSCRIPCION,
        nombre="Suscripción",
        descripcion="Evaluar tu solicitud de póliza sin pedirte documentos adicionales.",
        alcance=("Ingresos recurrentes", "Obligaciones vigentes"),
        vigencia_meses=12,
        entidades_origen=ENTIDADES,
        version_texto="suscripcion-2026.1",
    ),
    Finalidad(
        codigo=CodigoFinalidad.PREVENCION_FRAUDE,
        nombre="Prevención de fraude",
        descripcion="Verificar que las transacciones de tu póliza sean legítimas.",
        alcance=("Titularidad de cuentas", "Movimientos atípicos"),
        vigencia_meses=6,
        entidades_origen=ENTIDADES,
        version_texto="prevencion-fraude-2026.1",
    ),
)

_POR_CODIGO = {f.codigo: f for f in FINALIDADES}
_ENTIDADES_POR_ID = {e.id: e for e in ENTIDADES}


def finalidad(codigo: CodigoFinalidad | str) -> Finalidad:
    return _POR_CODIGO[CodigoFinalidad(codigo)]


def entidad(id_entidad: str) -> Entidad | None:
    return _ENTIDADES_POR_ID.get(id_entidad)


def validar_entidades(ids: tuple[str, ...] | list[str]) -> tuple[str, ...]:
    """Al menos una entidad, todas del catálogo y sin repetidas."""
    unicas = tuple(dict.fromkeys(ids))
    desconocidas = [i for i in unicas if i not in _ENTIDADES_POR_ID]
    if not unicas or desconocidas:
        raise EntidadesInvalidasError(
            "Debe autorizar al menos una entidad del catálogo",
            desconocidas=desconocidas,
            disponibles=[e.id for e in ENTIDADES],
        )
    return unicas
