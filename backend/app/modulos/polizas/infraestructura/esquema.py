"""Esquema de pólizas en PostgreSQL (database per bounded context).

La restricción `polizas_una_por_suscripcion` es la que impide que un reintento de la firma
emita una segunda póliza, aunque llegue a otra réplica del núcleo. Las pólizas de semilla no
tienen suscripción, y las claves nulas no chocan entre sí.

Las coberturas se guardan como JSON: son una copia inmutable de la oferta, no una entidad
que se consulte por separado. El esquema es idempotente: se aplica en cada arranque.
"""

from __future__ import annotations

TABLA = """
CREATE SCHEMA IF NOT EXISTS polizas;

CREATE SEQUENCE IF NOT EXISTS polizas.numero START WITH 1;

CREATE TABLE IF NOT EXISTS polizas.polizas (
    numero            TEXT         PRIMARY KEY,
    id_cliente        TEXT         NOT NULL,
    producto          TEXT         NOT NULL,
    inicio            DATE         NOT NULL,
    fin               DATE         NOT NULL,
    nombre_producto   TEXT,
    coberturas        JSONB        NOT NULL DEFAULT '[]'::jsonb,
    deducible         TEXT,
    valor_asegurado   TEXT,
    prima_mensual     BIGINT,
    moneda            TEXT,
    id_suscripcion    TEXT,
    id_firma          TEXT,
    emitida_en        TIMESTAMPTZ,
    canal             TEXT,
    id_documento      TEXT,
    id_decision       TEXT,
    CONSTRAINT polizas_una_por_suscripcion UNIQUE (id_suscripcion)
);

CREATE INDEX IF NOT EXISTS polizas_por_cliente ON polizas.polizas (id_cliente, inicio DESC);
"""

COLUMNAS = """
    numero, id_cliente, producto, inicio, fin, nombre_producto, coberturas, deducible,
    valor_asegurado, prima_mensual, moneda, id_suscripcion, id_firma, emitida_en, canal,
    id_documento, id_decision
"""

VALORES = """
    %(numero)s, %(id_cliente)s, %(producto)s, %(inicio)s, %(fin)s, %(nombre_producto)s,
    %(coberturas)s, %(deducible)s, %(valor_asegurado)s, %(prima_mensual)s, %(moneda)s,
    %(id_suscripcion)s, %(id_firma)s, %(emitida_en)s, %(canal)s, %(id_documento)s,
    %(id_decision)s
"""

GUARDAR = f"""
INSERT INTO polizas.polizas ({COLUMNAS}) VALUES ({VALORES})
ON CONFLICT (numero) DO UPDATE SET
    id_cliente = EXCLUDED.id_cliente, producto = EXCLUDED.producto, inicio = EXCLUDED.inicio,
    fin = EXCLUDED.fin, nombre_producto = EXCLUDED.nombre_producto,
    coberturas = EXCLUDED.coberturas, deducible = EXCLUDED.deducible,
    valor_asegurado = EXCLUDED.valor_asegurado, prima_mensual = EXCLUDED.prima_mensual,
    moneda = EXCLUDED.moneda, id_suscripcion = EXCLUDED.id_suscripcion,
    id_firma = EXCLUDED.id_firma, emitida_en = EXCLUDED.emitida_en, canal = EXCLUDED.canal,
    id_documento = EXCLUDED.id_documento, id_decision = EXCLUDED.id_decision
"""

EMITIR_SI_NUEVA = f"""
INSERT INTO polizas.polizas ({COLUMNAS}) VALUES ({VALORES})
ON CONFLICT ON CONSTRAINT polizas_una_por_suscripcion DO NOTHING
RETURNING {COLUMNAS}
"""

POR_NUMERO = f"SELECT {COLUMNAS} FROM polizas.polizas WHERE numero = %(numero)s"

POR_SUSCRIPCION = f"""
SELECT {COLUMNAS} FROM polizas.polizas WHERE id_suscripcion = %(id_suscripcion)s
"""

DEL_CLIENTE = f"""
SELECT {COLUMNAS} FROM polizas.polizas WHERE id_cliente = %(id_cliente)s ORDER BY inicio DESC
"""

SIGUIENTE_NUMERO = "SELECT nextval('polizas.numero') AS consecutivo"
