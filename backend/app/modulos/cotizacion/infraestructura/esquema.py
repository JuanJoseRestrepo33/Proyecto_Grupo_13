"""Esquema de cotización en PostgreSQL (database per bounded context).

El módulo tiene su propio esquema y su propio usuario en la instancia que comparte con la
auditoría: con permisos solo sobre `cotizacion`, no puede leer los datos de otro módulo,
igual que en el código (ARQ-01). El usuario crea el esquema él mismo al arrancar, así que
necesita permiso de `CREATE` sobre la base (`docs/devops.md`).

La cotización completa se guarda como JSONB: es un documento que no cambia después de
emitido (recotizar crea otra). En columnas quedan solo lo que se busca y lo que se ordena.

El esquema es idempotente: se puede aplicar en cada arranque.
"""

from __future__ import annotations

#: El prototipo arranca la numeración en QT-2026-08831; la base continúa desde ahí.
PRIMER_CONSECUTIVO = 8831

TABLA = f"""
CREATE SCHEMA IF NOT EXISTS cotizacion;

CREATE SEQUENCE IF NOT EXISTS cotizacion.consecutivo START WITH {PRIMER_CONSECUTIVO};

CREATE TABLE IF NOT EXISTS cotizacion.cotizaciones (
    id_cotizacion   TEXT         PRIMARY KEY,
    id_cliente      TEXT,
    huella          CHAR(64),
    creada_en       TIMESTAMPTZ  NOT NULL,
    expira_en       TIMESTAMPTZ  NOT NULL,
    datos           JSONB        NOT NULL,
    registrada_en   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS cotizaciones_por_huella
    ON cotizacion.cotizaciones (id_cliente, huella, expira_en DESC)
    WHERE huella IS NOT NULL;
"""

SIGUIENTE_CONSECUTIVO = "SELECT nextval('cotizacion.consecutivo') AS consecutivo"

INSERTAR = """
INSERT INTO cotizacion.cotizaciones (
    id_cotizacion, id_cliente, huella, creada_en, expira_en, datos
) VALUES (
    %(id_cotizacion)s, %(id_cliente)s, %(huella)s, %(creada_en)s, %(expira_en)s, %(datos)s
)
"""

COLUMNAS = "id_cotizacion, id_cliente, huella, creada_en, datos"

POR_ID = f"SELECT {COLUMNAS} FROM cotizacion.cotizaciones WHERE id_cotizacion = %(id)s"

VIGENTE_POR_HUELLA = f"""
SELECT {COLUMNAS} FROM cotizacion.cotizaciones
WHERE id_cliente = %(id_cliente)s AND huella = %(huella)s AND expira_en > %(ahora)s
ORDER BY creada_en DESC
LIMIT 1
"""
