"""Almacén del log de auditoría en PostgreSQL.

La asignación de secuencia y de hash anterior ocurre dentro de un advisory lock
transaccional: dos escritores concurrentes nunca ven el mismo último eslabón, que fue
exactamente el defecto que EXP-04 destapó y corrigió (criterio 5).
"""

from __future__ import annotations

from collections.abc import Mapping

import psycopg
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb

from ..dominio import cadena
from ..dominio.modelo import EventoAuditable, Registro
from . import esquema


def _jsonb(valor: Mapping[str, object]) -> Jsonb:
    return Jsonb(dict(valor), dumps=cadena.canonico)


def _a_registro(fila: Mapping[str, object]) -> Registro:
    evento = EventoAuditable(
        tipo=str(fila["tipo"]),
        actor=str(fila["actor"]),
        canal=str(fila["canal"]),
        ocurrido_en=fila["ocurrido_en"],  # type: ignore[arg-type]
        version_regla=fila["version_regla"],  # type: ignore[arg-type]
        variables_entrada=fila["variables_entrada"],  # type: ignore[arg-type]
        resultado=fila["resultado"],  # type: ignore[arg-type]
        referencias=fila["referencias"],  # type: ignore[arg-type]
        version_texto_legal=fila["version_texto_legal"],  # type: ignore[arg-type]
    )
    return Registro(
        secuencia=int(fila["secuencia"]),  # type: ignore[call-overload]
        id_decision=str(fila["id_decision"]),
        evento=evento,
        hash_anterior=str(fila["hash_anterior"]),
        hash=str(fila["hash"]),
    )


class AlmacenPostgres:
    """`tiempo_espera_conexion` acota cuánto puede tardar `/salud` o una escritura si la
    base no responde: sin él, un puerto que acepta la conexión y calla dejaría colgado al
    llamador. Es por intento, y `localhost` suele resolver a dos direcciones."""

    def __init__(self, dsn: str, tiempo_espera_conexion: int = 2) -> None:
        self._dsn = dsn
        self._tiempo_espera = tiempo_espera_conexion
        self._preparado = False

    def _conectar(self) -> psycopg.Connection:
        return psycopg.connect(self._dsn, row_factory=dict_row, connect_timeout=self._tiempo_espera)

    def preparar(self) -> None:
        """Aplica el esquema y los permisos. Idempotente."""
        with self._conectar() as conexion:
            conexion.execute(esquema.TABLA)
            conexion.execute(esquema.PERMISOS)
        self._preparado = True

    def verificar_conexion(self) -> None:
        with self._conectar() as conexion:
            conexion.execute("SELECT 1")

    def anexar(self, id_decision: str, evento: EventoAuditable) -> Registro:
        if not self._preparado:
            self.preparar()
        with self._conectar() as conexion, conexion.transaction():
            conexion.execute("SELECT pg_advisory_xact_lock(%s)", (esquema.CERROJO_SECUENCIA,))
            ultimo = conexion.execute(esquema.ULTIMO_ESLABON).fetchone()
            secuencia = int(ultimo["secuencia"]) + 1 if ultimo else 1
            anterior = str(ultimo["hash"]) if ultimo else cadena.HASH_GENESIS
            registro = cadena.encadenar(secuencia, anterior, id_decision, evento)
            conexion.execute(
                esquema.INSERTAR,
                {
                    "secuencia": registro.secuencia,
                    "id_decision": registro.id_decision,
                    "tipo": evento.tipo,
                    "actor": evento.actor,
                    "canal": evento.canal,
                    "ocurrido_en": evento.ocurrido_en,
                    "version_regla": evento.version_regla,
                    "variables_entrada": _jsonb(evento.variables_entrada),
                    "resultado": _jsonb(evento.resultado),
                    "referencias": _jsonb(evento.referencias),
                    "version_texto_legal": evento.version_texto_legal,
                    "hash_anterior": registro.hash_anterior,
                    "hash": registro.hash,
                },
            )
        return registro

    def obtener(self, id_decision: str) -> Registro | None:
        with self._conectar() as conexion:
            fila = conexion.execute(esquema.POR_ID, {"id_decision": id_decision}).fetchone()
        return _a_registro(fila) if fila else None

    def todos(self) -> tuple[Registro, ...]:
        with self._conectar() as conexion:
            filas = conexion.execute(esquema.TODOS).fetchall()
        return tuple(_a_registro(fila) for fila in filas)
