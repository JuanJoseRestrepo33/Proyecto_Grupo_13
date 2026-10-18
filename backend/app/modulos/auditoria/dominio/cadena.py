"""Cadena de hash del log de auditoría.

Cada registro lleva el hash del anterior y su propio hash cubre, además del evento, su
número de secuencia. Eso es lo que hace detectable cualquier alteración: cambiar un dato
cambia el hash de ese registro; borrar uno deja un hueco en la secuencia; insertar uno en
medio rompe el `hash_anterior` del siguiente.

El número de secuencia entra en el hash a propósito. En el experimento EXP-04, cuando
varios procesos escribían a la vez, el número se asignaba fuera del cerrojo y la
verificación reportaba eslabones rotos que no lo eran. El almacén asigna secuencia y
hash anterior dentro del mismo cerrojo, y este módulo los incluye en el hash.
"""

from __future__ import annotations

import hashlib
import json
from collections.abc import Iterable
from datetime import UTC

from .modelo import EslabonRoto, EventoAuditable, InformeDeIntegridad, Registro

HASH_GENESIS = "0" * 64


def canonico(valor: object) -> str:
    """JSON determinista: mismas claves en el mismo orden, sin espacios, UTF-8.

    Decimal, datetime y cualquier otro tipo no nativo se serializan con `str`, de modo que
    lo que se hashea es exactamente lo que se guarda.
    """
    return json.dumps(valor, sort_keys=True, separators=(",", ":"), ensure_ascii=False, default=str)


def calcular_hash(
    secuencia: int, hash_anterior: str, id_decision: str, evento: EventoAuditable
) -> str:
    carga = {
        "secuencia": secuencia,
        "hash_anterior": hash_anterior,
        "id_decision": id_decision,
        "tipo": evento.tipo,
        "actor": evento.actor,
        "canal": evento.canal,
        "ocurrido_en": evento.ocurrido_en.astimezone(UTC).isoformat(),
        "version_regla": evento.version_regla,
        "variables_entrada": dict(evento.variables_entrada),
        "resultado": dict(evento.resultado),
        "referencias": dict(evento.referencias),
        "version_texto_legal": evento.version_texto_legal,
    }
    return hashlib.sha256(canonico(carga).encode("utf-8")).hexdigest()


def encadenar(
    secuencia: int, hash_anterior: str, id_decision: str, evento: EventoAuditable
) -> Registro:
    return Registro(
        secuencia=secuencia,
        id_decision=id_decision,
        evento=evento,
        hash_anterior=hash_anterior,
        hash=calcular_hash(secuencia, hash_anterior, id_decision, evento),
    )


def verificar(registros: Iterable[Registro]) -> InformeDeIntegridad:
    """Recorre la cadena en orden y señala cada eslabón que no cuadra.

    Un registro alterado se reporta por `hash_no_coincide` y solo él: el siguiente sigue
    apuntando al hash que quedó guardado, no al recalculado (criterio 4).
    """
    rotos: list[EslabonRoto] = []
    esperado_anterior = HASH_GENESIS
    esperada_secuencia = 1
    total = 0
    for registro in registros:
        total += 1
        if registro.secuencia != esperada_secuencia:
            rotos.append(
                EslabonRoto(registro.secuencia, registro.id_decision, "secuencia_discontinua")
            )
        elif registro.hash_anterior != esperado_anterior:
            rotos.append(
                EslabonRoto(registro.secuencia, registro.id_decision, "hash_anterior_no_coincide")
            )
        elif (
            calcular_hash(
                registro.secuencia, registro.hash_anterior, registro.id_decision, registro.evento
            )
            != registro.hash
        ):
            rotos.append(EslabonRoto(registro.secuencia, registro.id_decision, "hash_no_coincide"))
        esperado_anterior = registro.hash
        esperada_secuencia = registro.secuencia + 1
    return InformeDeIntegridad(total=total, eslabones_rotos=tuple(rotos))
