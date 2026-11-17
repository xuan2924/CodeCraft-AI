-- Admission rows are bounded by active bucket capacity times policy limit (at most 1000).
CREATE TABLE sliding_window_rate_limit (
    bucket_hash varchar(64) NOT NULL,
    expires_at timestamptz NOT NULL
);
CREATE INDEX sliding_window_rate_limit_bucket_idx ON sliding_window_rate_limit(bucket_hash);
CREATE INDEX sliding_window_rate_limit_expiry_idx ON sliding_window_rate_limit(expires_at);
-- Preserve the remaining legacy fixed window conservatively during migration.
INSERT INTO sliding_window_rate_limit(bucket_hash, expires_at)
SELECT encode(sha256(convert_to('8:identity' || bucket_hash || ':60000:5', 'UTF8')), 'hex'), window_start + interval '1 minute'
FROM identity_rate_limit, LATERAL generate_series(1, attempts);
