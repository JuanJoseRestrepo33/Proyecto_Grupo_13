"""Esquema de suscripciones en PostgreSQL (database per bounded context).

`suscripciones_una_por_cotizacion` sostiene en la base que una cotización se acepte una sola
vez, aunque dos aceptaciones lleguen a réplicas distintas. Las condiciones de la oferta y la
trazabilidad se guardan como JSON: son copias inmutables, no entidades consultables.

El esquema es idempotente: se aplica en cada arranque.
"""

from __future__ import annotations

TABLA = """
CREATE SCHEMA IF NOT EXISTS suscripcion;

CREATE TABLE IF NOT EXISTS suscripcion.suscripciones (
    id_suscripcion  TEXT         PRIMARY KEY,
    id_cotizacion   TEXT         NOT NULL,
    id_cliente      TEXT         NOT NULL,
    condiciones     JSONB        NOT NULL,
    canal           TEXT         NOT NULL,
    aceptada_en     TIMESTAMPTZ  NOT NULL,
    estado          TEXT         NOT NULL,
    id_pago         TEXT,
    pagada_en       TIMESTAMPTZ,
    id_firma        TEXT,
    firmada_en      TIMESTAMPTZ,
    numero_poliza   TEXT,
    poliza_inicio   DATE,
    poliza_fin      DATE,
    emitida_en      TIMESTAMPTZ,
    trazabilidad    JSONB        NOT NULL DEFAULT '[]'::jsonb,
    CONSTRAINT suscripciones_una_por_cotizacion UNIQUE (id_cotizacion)
);

CREATE INDEX IF NOT EXISTS suscripciones_por_cliente
    ON suscripcion.suscripciones (id_cliente, aceptada_en DESC);
"""

COLUMNAS = """
    id_suscripcion, id_cotizacion, id_cliente, condiciones, canal, aceptada_en, estado, id_pago,
    pagada_en, id_firma, firmada_en, numero_poliza, poliza_inicio, poliza_fin, emitida_en,
    trazabilidad
"""

INSERTAR_SI_NUEVA = f"""
INSERT INTO suscripcion.suscripciones ({COLUMNAS}) VALUES (
    %(id_suscripcion)s, %(id_cotizacion)s, %(id_cliente)s, %(condiciones)s, %(canal)s,
    %(aceptada_en)s, %(estado)s, %(id_pago)s, %(pagada_en)s, %(id_firma)s, %(firmada_en)s,
    %(numero_poliza)s, %(poliza_inicio)s, %(poliza_fin)s, %(emitida_en)s, %(trazabilidad)s
)
ON CONFLICT ON CONSTRAINT suscripciones_una_por_cotizacion DO NOTHING
RETURNING {COLUMNAS}
"""

ACTUALIZAR = """
UPDATE suscripcion.suscripciones SET
    estado = %(estado)s, id_pago = %(id_pago)s, pagada_en = %(pagada_en)s,
    id_firma = %(id_firma)s, firmada_en = %(firmada_en)s, numero_poliza = %(numero_poliza)s,
    poliza_inicio = %(poliza_inicio)s, poliza_fin = %(poliza_fin)s,
    emitida_en = %(emitida_en)s, trazabilidad = %(trazabilidad)s
WHERE id_suscripcion = %(id_suscripcion)s
"""

POR_ID = f"""
SELECT {COLUMNAS} FROM suscripcion.suscripciones WHERE id_suscripcion = %(id_suscripcion)s
"""

POR_COTIZACION = f"""
SELECT {COLUMNAS} FROM suscripcion.suscripciones WHERE id_cotizacion = %(id_cotizacion)s
"""

DEL_CLIENTE = f"""
SELECT {COLUMNAS} FROM suscripcion.suscripciones
WHERE id_cliente = %(id_cliente)s ORDER BY aceptada_en DESC
"""
