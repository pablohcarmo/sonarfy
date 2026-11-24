ALTER TABLE Albums ADD COLUMN IF NOT EXISTS total_tracks INTEGER
    CONSTRAINT chk_album_total_tracks CHECK (total_tracks >= 0);
