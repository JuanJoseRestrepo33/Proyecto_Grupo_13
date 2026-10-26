"""Esquema de siniestros en PostgreSQL (database per bounded context).

El módulo tiene su esquema y su usuario en la instancia que comparte con la auditoría, con
permisos solo sobre `siniestros`; el usuario crea el esquema al arrancar
(`docs/devops.md`).

La unicidad de la clave de idempotencia por cliente vive en la base, no solo en el código:
así el doble envío (criterio 4) se sostiene tras un reinicio y entre instancias. Las claves
nulas no chocan entre sí.

El esquema es idempotente: se puede aplicar en cada arranque.
"""

from __future__ import annotations

TABLA = """
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
"""

SIGUIENTE_REFERENCIA = "SELECT nextval('siniestros.referencia') AS consecutivo"

COLUMNAS = """
    id_siniestro, referencia, id_cliente, numero_poliza, tipo_evento, ocurrido_el,
    descripcion, monto_estimado, canal, estado, reportado_en, clave_idempotencia
"""

INSERTAR_SI_NUEVO = f"""
INSERT INTO siniestros.siniestros ({COLUMNAS}) VALUES (
    %(id_siniestro)s, %(referencia)s, %(id_cliente)s, %(numero_poliza)s, %(tipo_evento)s,
    %(ocurrido_el)s, %(descripcion)s, %(monto_estimado)s, %(canal)s, %(estado)s,
    %(reportado_en)s, %(clave_idempotencia)s
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
