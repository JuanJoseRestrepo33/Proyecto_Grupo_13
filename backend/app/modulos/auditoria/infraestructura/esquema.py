"""Esquema del log de auditoría en PostgreSQL.

Tres capas impiden modificar o borrar un registro (criterio 2):

1. **Permisos de rol.** La aplicación se conecta como `solventa_app`, que solo tiene
   `SELECT` e `INSERT` sobre la tabla.
2. **Triggers.** Aunque alguien llegue con un rol más amplio, `UPDATE`, `DELETE` y
   `TRUNCATE` fallan con una excepción de la base.
3. **Cadena de hash.** Si un administrador salta las dos capas anteriores, la
   verificación de integridad señala exactamente el registro alterado.

El esquema es idempotente: se puede aplicar en cada arranque.
"""

from __future__ import annotations

ROL_APLICACION = "solventa_app"

# Clave del advisory lock que serializa la asignación de secuencia.
CERROJO_SECUENCIA = 7_304_001

TABLA = """
CREATE SCHEMA IF NOT EXISTS auditoria;

CREATE TABLE IF NOT EXISTS auditoria.registros (
    secuencia           BIGINT       PRIMARY KEY,
    id_decision         TEXT         NOT NULL UNIQUE,
    tipo                TEXT         NOT NULL,
    actor               TEXT         NOT NULL,
    canal               TEXT         NOT NULL,
    ocurrido_en         TIMESTAMPTZ  NOT NULL,
    version_regla       TEXT,
    variables_entrada   JSONB        NOT NULL,
    resultado           JSONB        NOT NULL,
    referencias         JSONB        NOT NULL,
    version_texto_legal TEXT,
    hash_anterior       CHAR(64)     NOT NULL,
    hash                CHAR(64)     NOT NULL UNIQUE,
    registrado_en       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE OR REPLACE FUNCTION auditoria.rechazar_modificacion() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'auditoria.registros es append-only: % no está permitido', TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS registros_inmutables ON auditoria.registros;
CREATE TRIGGER registros_inmutables
    BEFORE UPDATE OR DELETE ON auditoria.registros
    FOR EACH ROW EXECUTE FUNCTION auditoria.rechazar_modificacion();

DROP TRIGGER IF EXISTS registros_sin_truncate ON auditoria.registros;
CREATE TRIGGER registros_sin_truncate
    BEFORE TRUNCATE ON auditoria.registros
    FOR EACH STATEMENT EXECUTE FUNCTION auditoria.rechazar_modificacion();
"""

PERMISOS = f"""
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '{ROL_APLICACION}') THEN
        CREATE ROLE {ROL_APLICACION} NOLOGIN;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA auditoria TO {ROL_APLICACION};
GRANT SELECT, INSERT ON auditoria.registros TO {ROL_APLICACION};
REVOKE UPDATE, DELETE, TRUNCATE ON auditoria.registros FROM {ROL_APLICACION};
"""

ULTIMO_ESLABON = "SELECT secuencia, hash FROM auditoria.registros ORDER BY secuencia DESC LIMIT 1"

INSERTAR = """
INSERT INTO auditoria.registros (
    secuencia, id_decision, tipo, actor, canal, ocurrido_en, version_regla,
    variables_entrada, resultado, referencias, version_texto_legal, hash_anterior, hash
) VALUES (
    %(secuencia)s, %(id_decision)s, %(tipo)s, %(actor)s, %(canal)s, %(ocurrido_en)s,
    %(version_regla)s, %(variables_entrada)s, %(resultado)s, %(referencias)s,
    %(version_texto_legal)s, %(hash_anterior)s, %(hash)s
)
"""

COLUMNAS = """
    secuencia, id_decision, tipo, actor, canal, ocurrido_en, version_regla,
    variables_entrada, resultado, referencias, version_texto_legal, hash_anterior, hash
"""

POR_ID = f"SELECT {COLUMNAS} FROM auditoria.registros WHERE id_decision = %(id_decision)s"

TODOS = f"SELECT {COLUMNAS} FROM auditoria.registros ORDER BY secuencia"
