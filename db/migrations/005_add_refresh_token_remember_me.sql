-- Aplicar após 004; sessões existentes permanecem sem persistência de cookie.
BEGIN;
ALTER TABLE refresh_tokens ADD COLUMN remember_me BOOLEAN NOT NULL DEFAULT FALSE;
COMMIT;
