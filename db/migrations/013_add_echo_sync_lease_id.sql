-- Aplicar antes da aplicação que disponibiliza a sincronização de Ecos.
BEGIN;
ALTER TABLE echo_sync_state ADD COLUMN lease_id UUID;
COMMIT;
