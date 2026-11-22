-- Aplicar uma vez em bancos existentes antes do deploy de Ecos (PR 1).
-- Pre-requisito: 007_add_spotify_connection_status.sql (depende apenas de main ate 009; 010 permanece reservada).
BEGIN;

ALTER TABLE spotify_connection
    ADD COLUMN authorization_generation BIGINT NOT NULL DEFAULT 0;

COMMIT;
