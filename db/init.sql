-- ============================================================================
-- Solventa | Esquema de base de datos para los experimentos (EXP-03 y EXP-04)
--
-- La tabla de auditoria tiene TRES capas de proteccion:
--   1. Permisos: el rol de la aplicacion solo tiene INSERT y SELECT.
--   2. Disparadores: abortan cualquier UPDATE, DELETE o TRUNCATE.
--   3. Cadena de hashes: cada fila guarda el hash de la anterior, de modo que
--      una alteracion hecha por un administrador que desactive las capas 1 y 2
--      queda DETECTADA (el almacen es evidente ante manipulacion).
-- ============================================================================

-- ---------- Auditoria de decisiones (EXP-04) ----------
CREATE TABLE IF NOT EXISTS auditoria_decisiones (
    id                 BIGSERIAL PRIMARY KEY,
    id_decision        TEXT        NOT NULL UNIQUE,
    cliente            TEXT        NOT NULL,
    version_regla      TEXT        NOT NULL,
    variables_entrada  JSONB       NOT NULL,
    consentimiento_id  TEXT        NOT NULL,
    prima              NUMERIC(18,2) NOT NULL,
    origen_perfil      TEXT        NOT NULL,
    ts                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    hash_anterior      TEXT        NOT NULL,
    hash_propio        TEXT        NOT NULL
);

-- Capa 3: encadenamiento. El candado serializa las inserciones concurrentes
-- para que la cadena no se bifurque.
CREATE OR REPLACE FUNCTION auditoria_encadenar() RETURNS trigger AS $$
DECLARE
    previo TEXT;
BEGIN
    PERFORM pg_advisory_xact_lock(424242);
    SELECT hash_propio INTO previo
      FROM auditoria_decisiones ORDER BY id DESC LIMIT 1;
    NEW.hash_anterior := COALESCE(previo, 'GENESIS');
    NEW.hash_propio := encode(sha256(convert_to(
        NEW.hash_anterior || '|' || NEW.id_decision || '|' || NEW.cliente || '|' ||
        NEW.version_regla || '|' || NEW.variables_entrada::text || '|' ||
        NEW.consentimiento_id || '|' || NEW.prima::text || '|' || NEW.origen_perfil,
        'UTF8')), 'hex');
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS encadenar ON auditoria_decisiones;
CREATE TRIGGER encadenar BEFORE INSERT ON auditoria_decisiones
    FOR EACH ROW EXECUTE FUNCTION auditoria_encadenar();

-- Capa 2: bloqueo de modificacion.
CREATE OR REPLACE FUNCTION auditoria_bloquear() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'auditoria append-only: % prohibido', TG_OP;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS no_update ON auditoria_decisiones;
DROP TRIGGER IF EXISTS no_delete ON auditoria_decisiones;
DROP TRIGGER IF EXISTS no_truncate ON auditoria_decisiones;
CREATE TRIGGER no_update BEFORE UPDATE ON auditoria_decisiones
    FOR EACH ROW EXECUTE FUNCTION auditoria_bloquear();
CREATE TRIGGER no_delete BEFORE DELETE ON auditoria_decisiones
    FOR EACH ROW EXECUTE FUNCTION auditoria_bloquear();
CREATE TRIGGER no_truncate BEFORE TRUNCATE ON auditoria_decisiones
    FOR EACH STATEMENT EXECUTE FUNCTION auditoria_bloquear();

-- ---------- Transacciones de pago (EXP-03, medicion del RPO) ----------
CREATE TABLE IF NOT EXISTS transaccion_pago (
    id_transaccion  TEXT PRIMARY KEY,
    monto           NUMERIC(18,2) NOT NULL,
    estado          TEXT NOT NULL,
    ts              TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------- Capa 1: rol de la aplicacion con minimo privilegio ----------
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'solventa_app') THEN
        CREATE ROLE solventa_app LOGIN PASSWORD 'CAMBIAR_EN_PRODUCCION';
    END IF;
END
$$;

REVOKE ALL ON auditoria_decisiones FROM solventa_app;
GRANT  SELECT, INSERT ON auditoria_decisiones TO solventa_app;
GRANT  USAGE, SELECT ON SEQUENCE auditoria_decisiones_id_seq TO solventa_app;
GRANT  SELECT, INSERT ON transaccion_pago TO solventa_app;
