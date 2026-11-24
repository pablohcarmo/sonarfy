CREATE INDEX IF NOT EXISTS idx_reviews_user_review_desc
    ON Reviews (user_id, review_id DESC);
