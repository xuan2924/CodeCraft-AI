CREATE TABLE identity_captcha (
    context_hash text PRIMARY KEY,
    id text NOT NULL UNIQUE,
    answer_hash text NOT NULL,
    expires_at timestamptz NOT NULL
);
CREATE INDEX identity_captcha_expiry ON identity_captcha(expires_at);
