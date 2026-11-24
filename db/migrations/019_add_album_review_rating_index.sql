CREATE INDEX IF NOT EXISTS idx_reviews_album_rating_review_desc ON Reviews (album_id, rating DESC, review_id DESC);
