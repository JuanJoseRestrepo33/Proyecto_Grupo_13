"""DTO del contrato v1 de auditoría."""

from __future__ import annotations

from datetime import datetime

from pydantic import BaseModel, ConfigDict

from ..dominio.modelo import InformeDeIntegridad, Registro


class _Dto(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


class RegistroDto(_Dto):
    secuencia: int
    id_decision: str
    tipo: str
    actor: str
    canal: str
    ocurrido_en: datetime
    version_regla: str | None
    variables_entrada: dict[str, object]
    resultado: dict[str, object]
    referencias: dict[str, str]
    version_texto_legal: str | None
    hash_anterior: str
    hash: str


class EslabonRotoDto(_Dto):
    secuencia: int
    id_decision: str
    motivo: str


class InformeDeIntegridadDto(_Dto):
    total: int
    conforme: bool
    eslabones_rotos: tuple[EslabonRotoDto, ...]


def a_registro_dto(registro: Registro) -> RegistroDto:
    evento = registro.evento
    return RegistroDto(
        secuencia=registro.secuencia,
        id_decision=registro.id_decision,
        tipo=evento.tipo,
        actor=evento.actor,
        canal=evento.canal,
        ocurrido_en=evento.ocurrido_en,
        version_regla=evento.version_regla,
        variables_entrada=dict(evento.variables_entrada),
        resultado=dict(evento.resultado),
        referencias=dict(evento.referencias),
        version_texto_legal=evento.version_texto_legal,
        hash_anterior=registro.hash_anterior,
        hash=registro.hash,
    )


def a_informe_dto(informe: InformeDeIntegridad) -> InformeDeIntegridadDto:
    return InformeDeIntegridadDto(
        total=informe.total,
        conforme=informe.conforme,
        eslabones_rotos=tuple(
            EslabonRotoDto(secuencia=e.secuencia, id_decision=e.id_decision, motivo=e.motivo)
            for e in informe.eslabones_rotos
        ),
    )
