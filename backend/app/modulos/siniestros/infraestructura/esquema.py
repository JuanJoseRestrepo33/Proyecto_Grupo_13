"""Esquema de siniestros en PostgreSQL (database per bounded context).

El módulo tiene su esquema y su usuario en la instancia que comparte con la auditoría, con
permisos solo sobre `siniestros`; el usuario crea el esquema al arrancar
(`docs/devops.md`).

La unicidad de la clave de idempotencia por cliente vive en la base, no solo en el código:
así el doble envío (criterio 4) se sostiene tras un reinicio y entre instancias. Las claves
nulas no chocan entre sí.

El seguimiento (WEB-F06) agrega el historial de cambios de estado y los requerimientos. La
columna `estado_actualizado_en` se agrega solo si falta, para que una base del Sprint 2 se
actualice sola al arrancar sin bloquear la tabla en cada arranque. Un *advisory lock* de la
transacción serializa la preparación entre réplicas.

El esquema es idempotente: se puede aplicar en cada arranque.
"""

from __future__ import annotations

TABLA = """
-- Dos réplicas que arrancan a la vez preparan el esquema una detrás de la otra.
SELECT pg_advisory_xact_lock(4501050);

CREATE SCHEMA IF NOT EXISTS siniestros;

CREATE SEQUENCE IF NOT EXISTS siniestros.referencia START WITH 1;

CREATE TABLE IF NOT EXISTS siniestros.siniestros (
    id_siniestro        TEXT         PRIMARY KEY,
    referencia          TEXT         NOT NULL UNIQUE,
    id_cliente          TEXT         NOT NULL,
    numero_poliza       TEXT         NOT NULL,
    tipo_evento         TEXT         NOT NULL,
    ocurrido_el         DATE         NOT NULL,
    descripcion         TEXT         NOT NULL,
    monto_estimado      BIGINT,
    canal               TEXT         NOT NULL,
    estado              TEXT         NOT NULL,
    reportado_en        TIMESTAMPTZ  NOT NULL,
    clave_idempotencia  TEXT,
    CONSTRAINT siniestros_clave_por_cliente UNIQUE (id_cliente, clave_idempotencia)
);

CREATE INDEX IF NOT EXISTS siniestros_por_cliente
    ON siniestros.siniestros (id_cliente, reportado_en DESC);

CREATE TABLE IF NOT EXISTS siniestros.evidencias (
    id_evidencia     TEXT         PRIMARY KEY,
    id_siniestro     TEXT         NOT NULL REFERENCES siniestros.siniestros (id_siniestro),
    tipo_contenido   TEXT         NOT NULL,
    tamano           BIGINT       NOT NULL,
    nombre_original  TEXT,
    canal            TEXT         NOT NULL,
    cargada_en       TIMESTAMPTZ  NOT NULL,
    tomada_en        TIMESTAMPTZ,
    latitud          DOUBLE PRECISION,
    longitud         DOUBLE PRECISION,
    lugar            TEXT
);

CREATE INDEX IF NOT EXISTS evidencias_por_siniestro
    ON siniestros.evidencias (id_siniestro, cargada_en);

-- Solo si falta: ALTER TABLE bloquea la tabla aunque la columna ya exista.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'siniestros' AND table_name = 'siniestros'
          AND column_name = 'estado_actualizado_en'
    ) THEN
        ALTER TABLE siniestros.siniestros ADD COLUMN estado_actualizado_en TIMESTAMPTZ;
    END IF;
END
$$;

CREATE TABLE IF NOT EXISTS siniestros.eventos (
    id_evento        TEXT         PRIMARY KEY,
    orden            BIGSERIAL    NOT NULL,
    id_siniestro     TEXT         NOT NULL REFERENCES siniestros.siniestros (id_siniestro),
    estado_anterior  TEXT,
    estado_nuevo     TEXT         NOT NULL,
    ocurrido_en      TIMESTAMPTZ  NOT NULL,
    actor            TEXT         NOT NULL,
    motivo           TEXT,
    id_decision      TEXT
);

CREATE INDEX IF NOT EXISTS eventos_por_siniestro ON siniestros.eventos (id_siniestro, orden);

CREATE TABLE IF NOT EXISTS siniestros.requerimientos (
    id_requerimiento  TEXT         PRIMARY KEY,
    orden             BIGSERIAL    NOT NULL,
    id_siniestro      TEXT         NOT NULL REFERENCES siniestros.siniestros (id_siniestro),
    descripcion       TEXT         NOT NULL,
    creado_en         TIMESTAMPTZ  NOT NULL,
    id_evento         TEXT         NOT NULL,
    atendido_en       TIMESTAMPTZ,
    id_evidencia      TEXT
);

CREATE INDEX IF NOT EXISTS requerimientos_por_siniestro
    ON siniestros.requerimientos (id_siniestro, orden);
"""

SIGUIENTE_REFERENCIA = "SELECT nextval('siniestros.referencia') AS consecutivo"

COLUMNAS = """
    id_siniestro, referencia, id_cliente, numero_poliza, tipo_evento, ocurrido_el,
    descripcion, monto_estimado, canal, estado, reportado_en, clave_idempotencia,
    estado_actualizado_en
"""

INSERTAR_SI_NUEVO = f"""
INSERT INTO siniestros.siniestros ({COLUMNAS}) VALUES (
    %(id_siniestro)s, %(referencia)s, %(id_cliente)s, %(numero_poliza)s, %(tipo_evento)s,
    %(ocurrido_el)s, %(descripcion)s, %(monto_estimado)s, %(canal)s, %(estado)s,
    %(reportado_en)s, %(clave_idempotencia)s, %(estado_actualizado_en)s
)
ON CONFLICT ON CONSTRAINT siniestros_clave_por_cliente DO NOTHING
RETURNING {COLUMNAS}
"""

POR_CLAVE = f"""
SELECT {COLUMNAS} FROM siniestros.siniestros
WHERE id_cliente = %(id_cliente)s AND clave_idempotencia = %(clave_idempotencia)s
"""

POR_ID = f"""
SELECT {COLUMNAS} FROM siniestros.siniestros
WHERE id_cliente = %(id_cliente)s AND id_siniestro = %(id_siniestro)s
"""

DEL_CLIENTE = f"""
SELECT {COLUMNAS} FROM siniestros.siniestros
WHERE id_cliente = %(id_cliente)s
ORDER BY reportado_en DESC
"""

COLUMNAS_EVIDENCIA = """
    id_evidencia, id_siniestro, tipo_contenido, tamano, nombre_original, canal, cargada_en,
    tomada_en, latitud, longitud, lugar
"""

INSERTAR_EVIDENCIA = f"""
INSERT INTO siniestros.evidencias ({COLUMNAS_EVIDENCIA}) VALUES (
    %(id_evidencia)s, %(id_siniestro)s, %(tipo_contenido)s, %(tamano)s, %(nombre_original)s,
    %(canal)s, %(cargada_en)s, %(tomada_en)s, %(latitud)s, %(longitud)s, %(lugar)s
)
"""

EVIDENCIAS = f"""
SELECT {COLUMNAS_EVIDENCIA} FROM siniestros.evidencias
WHERE id_siniestro = %(id_siniestro)s
ORDER BY cargada_en, id_evidencia
"""

POR_ID_INTERNO = f"""
SELECT {COLUMNAS} FROM siniestros.siniestros WHERE id_siniestro = %(id_siniestro)s
"""

CAMBIAR_ESTADO = """
UPDATE siniestros.siniestros
SET estado = %(nuevo)s, estado_actualizado_en = %(instante)s
WHERE id_siniestro = %(id_siniestro)s AND estado = %(anterior)s
"""

COLUMNAS_EVENTO = """
    id_evento, id_siniestro, estado_anterior, estado_nuevo, ocurrido_en, actor, motivo,
    id_decision
"""

INSERTAR_EVENTO = f"""
INSERT INTO siniestros.eventos ({COLUMNAS_EVENTO}) VALUES (
    %(id_evento)s, %(id_siniestro)s, %(estado_anterior)s, %(estado_nuevo)s, %(ocurrido_en)s,
    %(actor)s, %(motivo)s, %(id_decision)s
)
"""

EVENTOS = f"""
SELECT {COLUMNAS_EVENTO} FROM siniestros.eventos
WHERE id_siniestro = %(id_siniestro)s ORDER BY orden
"""

COLUMNAS_REQUERIMIENTO = """
    id_requerimiento, id_siniestro, descripcion, creado_en, id_evento, atendido_en, id_evidencia
"""

INSERTAR_REQUERIMIENTO = f"""
INSERT INTO siniestros.requerimientos ({COLUMNAS_REQUERIMIENTO}) VALUES (
    %(id_requerimiento)s, %(id_siniestro)s, %(descripcion)s, %(creado_en)s, %(id_evento)s,
    %(atendido_en)s, %(id_evidencia)s
)
"""

REQUERIMIENTOS = f"""
SELECT {COLUMNAS_REQUERIMIENTO} FROM siniestros.requerimientos
WHERE id_siniestro = %(id_siniestro)s ORDER BY orden
"""

ATENDER_REQUERIMIENTO = """
UPDATE siniestros.requerimientos
SET atendido_en = %(instante)s, id_evidencia = %(id_evidencia)s
WHERE id_requerimiento = %(id_requerimiento)s AND atendido_en IS NULL
"""
