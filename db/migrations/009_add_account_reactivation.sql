ALTER TABLE users ADD COLUMN access_token_version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE reactivation_mail_outbox (
    event_id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    recipient VARCHAR(150) NOT NULL,
    available_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_id UUID,
    lease_until TIMESTAMP WITH TIME ZONE,
    delivered_at TIMESTAMP WITH TIME ZONE,
    CHECK ((lease_id IS NULL) = (lease_until IS NULL))
);
CREATE INDEX idx_reactivation_mail_pending ON reactivation_mail_outbox (available_at)
    WHERE delivered_at IS NULL;
