"""Dispositivos y notificaciones en PostgreSQL, en el esquema `notificaciones`.

`notificaciones_una_por_evento` sostiene en la base que un evento entregado dos veces, o a
dos réplicas, deje un solo push. El esquema es idempotente: se aplica en cada arranque.
"""

from __future__ import annotations

from collections.abc import Mapping
from datetime import datetime

import psycopg
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb

from ..dominio.modelo import Dispositivo, EstadoEntrega, Notificacion, Plataforma

TABLA = """
SELECT pg_advisory_xact_lock(4501061);

CREATE SCHEMA IF NOT EXISTS notificaciones;

CREATE TABLE IF NOT EXISTS notificaciones.dispositivos (
    id_dispositivo  TEXT         PRIMARY KEY,
    id_cliente      TEXT         NOT NULL,
    token           TEXT         NOT NULL,
    plataforma      TEXT         NOT NULL,
    registrado_en   TIMESTAMPTZ  NOT NULL,
    actualizado_en  TIMESTAMPTZ  NOT NULL
);

CREATE INDEX IF NOT EXISTS dispositivos_por_cliente
    ON notificaciones.dispositivos (id_cliente);

CREATE TABLE IF NOT EXISTS notificaciones.notificaciones (
    id_notificacion  TEXT         PRIMARY KEY,
    orden            BIGSERIAL    NOT NULL,
    id_cliente       TEXT         NOT NULL,
    id_evento        TEXT         NOT NULL,
    titulo           TEXT         NOT NULL,
    cuerpo           TEXT         NOT NULL,
    datos            JSONB        NOT NULL,
    creada_en        TIMESTAMPTZ,
    estado           TEXT         NOT NULL,
    dispositivos     INTEGER      NOT NULL,
    leida_en         TIMESTAMPTZ,
    CONSTRAINT notificaciones_una_por_evento UNIQUE (id_cliente, id_evento)
);

CREATE INDEX IF NOT EXISTS notificaciones_por_cliente
    ON notificaciones.notificaciones (id_cliente, orden DESC);
"""

_DISPOSITIVO = "id_dispositivo, id_cliente, token, plataforma, registrado_en, actualizado_en"
_NOTIFICACION = """
    id_notificacion, id_cliente, id_evento, titulo, cuerpo, datos, creada_en, estado,
    dispositivos, leida_en
"""


def _a_dispositivo(fila: Mapping[str, object]) -> Dispositivo:
    return Dispositivo(
        id_dispositivo=str(fila["id_dispositivo"]),
        id_cliente=str(fila["id_cliente"]),
        token=str(fila["token"]),
        plataforma=Plataforma(fila["plataforma"]),
        registrado_en=fila["registrado_en"],  # type: ignore[arg-type]
        actualizado_en=fila["actualizado_en"],  # type: ignore[arg-type]
    )


def _a_notificacion(fila: Mapping[str, object]) -> Notificacion:
    return Notificacion(
        id_notificacion=str(fila["id_notificacion"]),
        id_cliente=str(fila["id_cliente"]),
        id_evento=str(fila["id_evento"]),
        titulo=str(fila["titulo"]),
        cuerpo=str(fila["cuerpo"]),
        datos=dict(fila["datos"]),  # type: ignore[call-overload]
        creada_en=fila["creada_en"],  # type: ignore[arg-type]
        estado=EstadoEntrega(fila["estado"]),
        dispositivos=int(fila["dispositivos"]),  # type: ignore[call-overload]
        leida_en=fila["leida_en"],  # type: ignore[arg-type]
    )


class _Base:
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


class DispositivosPostgres(_Base):
    def registrar(self, dispositivo: Dispositivo) -> tuple[Dispositivo, bool]:
        with self._conectar() as conexion:
            fila = conexion.execute(
                f"""
                INSERT INTO notificaciones.dispositivos ({_DISPOSITIVO})
                VALUES (%(id)s, %(cliente)s, %(token)s, %(plataforma)s, %(ahora)s, %(ahora)s)
                ON CONFLICT (id_dispositivo) DO UPDATE SET
                    id_cliente = EXCLUDED.id_cliente, plataforma = EXCLUDED.plataforma,
                    actualizado_en = EXCLUDED.actualizado_en
                RETURNING {_DISPOSITIVO}, (xmax = 0) AS nuevo
                """,
                {
                    "id": dispositivo.id_dispositivo,
                    "cliente": dispositivo.id_cliente,
                    "token": dispositivo.token,
                    "plataforma": str(dispositivo.plataforma),
                    "ahora": dispositivo.actualizado_en,
                },
            ).fetchone()
        return _a_dispositivo(fila), bool(fila["nuevo"])  # type: ignore[index, arg-type]

    def dar_de_baja(self, id_cliente: str, id_dispositivo: str) -> bool:
        with self._conectar() as conexion:
            cursor = conexion.execute(
                "DELETE FROM notificaciones.dispositivos "
                "WHERE id_dispositivo = %(id)s AND id_cliente = %(cliente)s",
                {"id": id_dispositivo, "cliente": id_cliente},
            )
            return cursor.rowcount == 1

    def del_cliente(self, id_cliente: str) -> tuple[Dispositivo, ...]:
        with self._conectar() as conexion:
            filas = conexion.execute(
                f"SELECT {_DISPOSITIVO} FROM notificaciones.dispositivos "
                "WHERE id_cliente = %(cliente)s ORDER BY registrado_en",
                {"cliente": id_cliente},
            ).fetchall()
        return tuple(_a_dispositivo(f) for f in filas)


class NotificacionesPostgres(_Base):
    def guardar_si_nueva(self, notificacion: Notificacion) -> tuple[Notificacion, bool]:
        with self._conectar() as conexion:
            fila = conexion.execute(
                f"""
                INSERT INTO notificaciones.notificaciones ({_NOTIFICACION}) VALUES (
                    %(id)s, %(cliente)s, %(evento)s, %(titulo)s, %(cuerpo)s, %(datos)s,
                    %(creada_en)s, %(estado)s, %(dispositivos)s, %(leida_en)s
                )
                ON CONFLICT ON CONSTRAINT notificaciones_una_por_evento DO NOTHING
                RETURNING {_NOTIFICACION}
                """,
                {
                    "id": notificacion.id_notificacion,
                    "cliente": notificacion.id_cliente,
                    "evento": notificacion.id_evento,
                    "titulo": notificacion.titulo,
                    "cuerpo": notificacion.cuerpo,
                    "datos": Jsonb(notificacion.datos),
                    "creada_en": notificacion.creada_en,
                    "estado": str(notificacion.estado),
                    "dispositivos": notificacion.dispositivos,
                    "leida_en": notificacion.leida_en,
                },
            ).fetchone()
            if fila is not None:
                return _a_notificacion(fila), True
            fila = conexion.execute(
                f"SELECT {_NOTIFICACION} FROM notificaciones.notificaciones "
                "WHERE id_cliente = %(cliente)s AND id_evento = %(evento)s",
                {"cliente": notificacion.id_cliente, "evento": notificacion.id_evento},
            ).fetchone()
        return _a_notificacion(fila), False  # type: ignore[arg-type]

    def actualizar(self, notificacion: Notificacion) -> None:
        with self._conectar() as conexion:
            conexion.execute(
                "UPDATE notificaciones.notificaciones SET estado = %(estado)s, "
                "dispositivos = %(dispositivos)s, leida_en = %(leida_en)s "
                "WHERE id_notificacion = %(id)s",
                {
                    "id": notificacion.id_notificacion,
                    "estado": str(notificacion.estado),
                    "dispositivos": notificacion.dispositivos,
                    "leida_en": notificacion.leida_en,
                },
            )

    def del_cliente(self, id_cliente: str, limite: int) -> tuple[Notificacion, ...]:
        with self._conectar() as conexion:
            filas = conexion.execute(
                f"SELECT {_NOTIFICACION} FROM notificaciones.notificaciones "
                "WHERE id_cliente = %(cliente)s ORDER BY orden DESC LIMIT %(limite)s",
                {"cliente": id_cliente, "limite": limite},
            ).fetchall()
        return tuple(_a_notificacion(f) for f in filas)

    def marcar_leida(self, id_cliente: str, id_notificacion: str, instante: datetime) -> bool:
        with self._conectar() as conexion:
            cursor = conexion.execute(
                "UPDATE notificaciones.notificaciones "
                "SET leida_en = COALESCE(leida_en, %(instante)s) "
                "WHERE id_notificacion = %(id)s AND id_cliente = %(cliente)s",
                {"id": id_notificacion, "cliente": id_cliente, "instante": instante},
            )
            return cursor.rowcount == 1
