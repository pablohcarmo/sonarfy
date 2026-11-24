CREATE INDEX IF NOT EXISTS idx_reviews_user_rating_review_desc
    ON Reviews (user_id, rating DESC, review_id DESC);
