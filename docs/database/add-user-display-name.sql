BEGIN;

ALTER TABLE Users ADD COLUMN IF NOT EXISTS display_name VARCHAR(255);

UPDATE Users
SET display_name = LEFT(TRIM(CONCAT_WS(' ', name, surname)), 255)
WHERE display_name IS NULL;

COMMIT;
