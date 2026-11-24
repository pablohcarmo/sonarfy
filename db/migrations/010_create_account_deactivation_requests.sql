CREATE TABLE account_deactivation_requests (
    user_id BIGINT PRIMARY KEY REFERENCES users(user_id) ON DELETE CASCADE,
    request_id UUID NOT NULL UNIQUE,
    token_hash VARCHAR(64) NOT NULL UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    issued_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at TIMESTAMP WITH TIME ZONE,
    CHECK (expires_at > issued_at),
    CHECK (consumed_at IS NULL OR (consumed_at >= issued_at AND consumed_at < expires_at))
);
