CREATE INDEX IF NOT EXISTS idx_reviews_album_review_desc ON Reviews (album_id, review_id DESC);
