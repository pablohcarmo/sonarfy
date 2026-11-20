CREATE INDEX IF NOT EXISTS idx_user_following_following_follower
    ON User_Following (following_id, follower_id);
