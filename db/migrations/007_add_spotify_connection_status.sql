-- Aplicar antes do deploy; preserva conexoes existentes como CONNECTED.
BEGIN;
ALTER TABLE spotify_connection
    ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'CONNECTED',
    ALTER COLUMN access_token_encrypted DROP NOT NULL,
    ALTER COLUMN refresh_token_encrypted DROP NOT NULL,
    ADD CONSTRAINT spotify_connection_status_tokens CHECK (
        (status = 'CONNECTED' AND access_token_encrypted IS NOT NULL AND refresh_token_encrypted IS NOT NULL)
        OR (status = 'REAUTHORIZATION_REQUIRED' AND access_token_encrypted IS NULL AND refresh_token_encrypted IS NULL));
COMMIT;
