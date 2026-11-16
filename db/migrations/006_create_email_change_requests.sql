BEGIN;
CREATE TABLE email_change_requests (
    user_id BIGINT PRIMARY KEY REFERENCES users(user_id) ON DELETE CASCADE,
    request_id UUID NOT NULL UNIQUE,
    new_email VARCHAR(150) NOT NULL,
    issued_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at TIMESTAMP WITH TIME ZONE,
    CHECK (expires_at > issued_at)
);
CREATE TABLE email_change_mail_outbox (
    event_id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    request_id UUID NOT NULL,
    kind VARCHAR(20) NOT NULL CHECK (kind IN ('CONFIRM_NEW_EMAIL', 'NOTIFY_OLD_EMAIL')),
    recipient VARCHAR(150) NOT NULL,
    available_at TIMESTAMP WITH TIME ZONE NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    lease_id UUID,
    lease_until TIMESTAMP WITH TIME ZONE,
    delivered_at TIMESTAMP WITH TIME ZONE,
    CHECK ((lease_id IS NULL) = (lease_until IS NULL))
);
CREATE INDEX idx_email_change_mail_pending ON email_change_mail_outbox (available_at)
    WHERE delivered_at IS NULL;
COMMIT;
