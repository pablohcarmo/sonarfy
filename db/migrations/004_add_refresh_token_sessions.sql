-- Aplicar após 003, inclusive em bancos de validação que já receberam o PR.
BEGIN;
ALTER TABLE refresh_tokens ADD COLUMN session_id UUID;
UPDATE refresh_tokens SET session_id = gen_random_uuid();
ALTER TABLE refresh_tokens ALTER COLUMN session_id SET NOT NULL;
ALTER TABLE refresh_tokens ADD COLUMN used_at TIMESTAMP WITH TIME ZONE;
CREATE INDEX idx_refresh_tokens_session_id ON refresh_tokens (session_id);
COMMIT;
