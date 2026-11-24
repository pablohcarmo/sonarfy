BEGIN;

CREATE TABLE account_deactivation_mail_outbox (
    user_id BIGINT PRIMARY KEY REFERENCES users(user_id) ON DELETE CASCADE,
    request_id UUID NOT NULL UNIQUE,
    recipient VARCHAR(150) NOT NULL,
    available_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT statement_timestamp(),
    lease_id UUID,
    lease_until TIMESTAMP WITH TIME ZONE,
    delivered_at TIMESTAMP WITH TIME ZONE,
    CHECK ((lease_id IS NULL) = (lease_until IS NULL))
);

COMMIT;
