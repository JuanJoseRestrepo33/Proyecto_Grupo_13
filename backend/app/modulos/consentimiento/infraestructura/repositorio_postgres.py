"""Repositorio de consentimientos en PostgreSQL (ARQ-05), en el esquema `consentimiento`.

Con los consentimientos en Cloud SQL, todas las réplicas del núcleo leen la misma fuente de
verdad: una revocación hecha en una réplica la ve la siguiente consulta de cualquier otra.
Es lo que permite verificar el consentimiento antes de usar la caché de perfiles de cada
réplica, sin Redis (RC-04, reemplazo de D-04 de EXP-04).

El histórico de eventos se guarda como JSON junto al consentimiento: siempre se lee entero y
nunca se consulta por separado. El esquema es idempotente y un *advisory lock* serializa su
preparación entre réplicas.
"""

from __future__ import annotations

from collections.abc import Mapping
from datetime import datetime

import psycopg
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb

from ..dominio.catalogo import CodigoFinalidad
from ..dominio.modelo import Consentimiento, EventoConsentimiento, TipoEvento

TABLA = """
SELECT pg_advisory_xact_lock(4501051);

CREATE SCHEMA IF NOT EXISTS consentimiento;

CREATE TABLE IF NOT EXISTS consentimiento.consentimientos (
    id_consentimiento  TEXT         PRIMARY KEY,
    id_cliente         TEXT         NOT NULL,
    finalidad          TEXT         NOT NULL,
    entidades_origen   JSONB        NOT NULL,
    canal              TEXT         NOT NULL,
    otorgado_en        TIMESTAMPTZ  NOT NULL,
    vence_en           TIMESTAMPTZ  NOT NULL,
    version_texto      TEXT         NOT NULL,
    revocado_en        TIMESTAMPTZ,
    eventos            JSONB        NOT NULL
);

CREATE INDEX IF NOT EXISTS consentimientos_por_cliente
    ON consentimiento.consentimientos (id_cliente, otorgado_en);
"""

COLUMNAS = """
    id_consentimiento, id_cliente, finalidad, entidades_origen, canal, otorgado_en, vence_en,
    version_texto, revocado_en, eventos
"""

GUARDAR = f"""
INSERT INTO consentimiento.consentimientos ({COLUMNAS}) VALUES (
    %(id_consentimiento)s, %(id_cliente)s, %(finalidad)s, %(entidades_origen)s, %(canal)s,
    %(otorgado_en)s, %(vence_en)s, %(version_texto)s, %(revocado_en)s, %(eventos)s
)
ON CONFLICT (id_consentimiento) DO UPDATE SET
    vence_en = EXCLUDED.vence_en, version_texto = EXCLUDED.version_texto,
    revocado_en = EXCLUDED.revocado_en, eventos = EXCLUDED.eventos
"""

POR_ID = f"""
SELECT {COLUMNAS} FROM consentimiento.consentimientos
WHERE id_consentimiento = %(id_consentimiento)s
"""

DEL_CLIENTE = f"""
SELECT {COLUMNAS} FROM consentimiento.consentimientos
WHERE id_cliente = %(id_cliente)s ORDER BY otorgado_en, id_consentimiento
"""


def _evento_a_json(evento: EventoConsentimiento) -> dict[str, str]:
    return {
        "tipo": str(evento.tipo),
        "ocurrido_en": evento.ocurrido_en.isoformat(),
        "canal": evento.canal,
        "version_texto": evento.version_texto,
    }


def _evento_de_json(datos: Mapping[str, str]) -> EventoConsentimiento:
    return EventoConsentimiento(
        tipo=TipoEvento(datos["tipo"]),
        ocurrido_en=datetime.fromisoformat(datos["ocurrido_en"]),
        canal=datos["canal"],
        version_texto=datos["version_texto"],
    )


def _a_consentimiento(fila: Mapping[str, object]) -> Consentimiento:
    return Consentimiento(
        id_consentimiento=str(fila["id_consentimiento"]),
        id_cliente=str(fila["id_cliente"]),
        finalidad=CodigoFinalidad(fila["finalidad"]),
        entidades_origen=tuple(fila["entidades_origen"]),  # type: ignore[arg-type]
        canal=str(fila["canal"]),
        otorgado_en=fila["otorgado_en"],  # type: ignore[arg-type]
        vence_en=fila["vence_en"],  # type: ignore[arg-type]
        version_texto=str(fila["version_texto"]),
        revocado_en=fila["revocado_en"],  # type: ignore[arg-type]
        eventos=tuple(_evento_de_json(e) for e in fila["eventos"]),  # type: ignore[attr-defined]
    )


class RepositorioPostgres:
    def __init__(self, dsn: str, tiempo_espera_conexion: int = 2) -> None:
        self._dsn = dsn
        self._tiempo_espera = tiempo_espera_conexion
        self._preparado = False

    def _abrir(self) -> psycopg.Connection:
        return psycopg.connect(self._dsn, row_factory=dict_row, connect_timeout=self._tiempo_espera)

    def _conectar(self) -> psycopg.Connection:
        if not self._preparado:
            self.preparar()
        return self._abrir()

    def preparar(self) -> None:
        with self._abrir() as conexion:
            conexion.execute(TABLA)
        self._preparado = True

    def verificar_conexion(self) -> None:
        with self._abrir() as conexion:
            conexion.execute("SELECT 1")

    def guardar(self, consentimiento: Consentimiento) -> None:
        with self._conectar() as conexion:
            conexion.execute(
                GUARDAR,
                {
                    "id_consentimiento": consentimiento.id_consentimiento,
                    "id_cliente": consentimiento.id_cliente,
                    "finalidad": str(consentimiento.finalidad),
                    "entidades_origen": Jsonb(list(consentimiento.entidades_origen)),
                    "canal": consentimiento.canal,
                    "otorgado_en": consentimiento.otorgado_en,
                    "vence_en": consentimiento.vence_en,
                    "version_texto": consentimiento.version_texto,
                    "revocado_en": consentimiento.revocado_en,
                    "eventos": Jsonb([_evento_a_json(e) for e in consentimiento.eventos]),
                },
            )

    def obtener(self, id_consentimiento: str) -> Consentimiento | None:
        with self._conectar() as conexion:
            fila = conexion.execute(POR_ID, {"id_consentimiento": id_consentimiento}).fetchone()
        return _a_consentimiento(fila) if fila else None

    def del_cliente(self, id_cliente: str) -> tuple[Consentimiento, ...]:
        with self._conectar() as conexion:
            filas = conexion.execute(DEL_CLIENTE, {"id_cliente": id_cliente}).fetchall()
        return tuple(_a_consentimiento(f) for f in filas)
