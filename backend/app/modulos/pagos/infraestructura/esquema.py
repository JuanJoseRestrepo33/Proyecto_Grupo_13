"""Esquema de pagos en PostgreSQL (database per bounded context).

Las dos reglas que evitan el cobro doble viven en la base y no solo en el código, para que
se sostengan entre réplicas del núcleo y tras un reinicio:

- `pagos_clave_unica`: una clave de idempotencia es un único intento.
- `pagos_un_activo_por_concepto`: índice único parcial; un concepto tiene a lo sumo un pago
  `procesando`, `pendiente` o `aprobado`.

El esquema es idempotente: se puede aplicar en cada arranque.
"""

from __future__ import annotations

TABLA = """
CREATE SCHEMA IF NOT EXISTS pagos;

CREATE TABLE IF NOT EXISTS pagos.pagos (
    id_pago             TEXT         PRIMARY KEY,
    -- Orden de llegada: dos intentos del mismo instante se distinguen sin depender del id.
    orden               BIGSERIAL    NOT NULL,
    concepto            TEXT         NOT NULL,
    id_cliente          TEXT         NOT NULL,
    medio               TEXT         NOT NULL,
    monto               BIGINT       NOT NULL,
    moneda              TEXT         NOT NULL,
    clave_idempotencia  TEXT         NOT NULL,
    canal               TEXT         NOT NULL,
    estado              TEXT         NOT NULL,
    referencia          TEXT,
    creado_en           TIMESTAMPTZ  NOT NULL,
    actualizado_en      TIMESTAMPTZ,
    id_decision         TEXT,
    CONSTRAINT pagos_clave_unica UNIQUE (clave_idempotencia)
);

CREATE UNIQUE INDEX IF NOT EXISTS pagos_un_activo_por_concepto
    ON pagos.pagos (concepto) WHERE estado IN ('procesando', 'pendiente', 'aprobado');

CREATE INDEX IF NOT EXISTS pagos_por_concepto ON pagos.pagos (concepto, orden);
"""

COLUMNAS = """
    id_pago, concepto, id_cliente, medio, monto, moneda, clave_idempotencia, canal, estado,
    referencia, creado_en, actualizado_en, id_decision
"""

INSERTAR_SI_LIBRE = f"""
INSERT INTO pagos.pagos ({COLUMNAS}) VALUES (
    %(id_pago)s, %(concepto)s, %(id_cliente)s, %(medio)s, %(monto)s, %(moneda)s,
    %(clave_idempotencia)s, %(canal)s, %(estado)s, %(referencia)s, %(creado_en)s,
    %(actualizado_en)s, %(id_decision)s
)
ON CONFLICT DO NOTHING
RETURNING {COLUMNAS}
"""

POR_CLAVE = f"SELECT {COLUMNAS} FROM pagos.pagos WHERE clave_idempotencia = %(clave)s"

ACTIVO_DEL_CONCEPTO = f"""
SELECT {COLUMNAS} FROM pagos.pagos
WHERE concepto = %(concepto)s AND estado IN ('procesando', 'pendiente', 'aprobado')
"""

ACTUALIZAR = """
UPDATE pagos.pagos
SET estado = %(estado)s, referencia = %(referencia)s, actualizado_en = %(actualizado_en)s,
    id_decision = %(id_decision)s
WHERE id_pago = %(id_pago)s
"""

LIBERAR = "DELETE FROM pagos.pagos WHERE id_pago = %(id_pago)s AND estado = 'procesando'"

POR_ID = f"SELECT {COLUMNAS} FROM pagos.pagos WHERE id_pago = %(id_pago)s"

DEL_CONCEPTO = f"""
SELECT {COLUMNAS} FROM pagos.pagos WHERE concepto = %(concepto)s ORDER BY orden
"""
