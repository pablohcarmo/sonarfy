-- Aplicar uma vez antes de executar a aplicação com o controle de versão OAuth.
ALTER TABLE Users
    ADD COLUMN spotify_authorization_version BIGINT NOT NULL DEFAULT 0;
