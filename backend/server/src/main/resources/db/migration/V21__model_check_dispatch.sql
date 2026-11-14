CREATE TABLE personal_model_check_authority (
    check_id uuid PRIMARY KEY,
    user_id uuid NOT NULL,
    session_hash varchar(64) NOT NULL,
    auth_version bigint NOT NULL CHECK (auth_version > 0),
    not_after timestamptz NOT NULL,
    worker_id uuid
);
CREATE TABLE personal_model_check_request (
    id uuid PRIMARY KEY,
    check_id uuid NOT NULL,
    ordinal integer NOT NULL CHECK (ordinal BETWEEN 1 AND 3),
    worker_id uuid NOT NULL,
    status varchar(24) NOT NULL CHECK (status IN ('DISPATCHED','SUCCEEDED','FAILED','INTERRUPTED')),
    dispatched_at timestamptz NOT NULL,
    not_after timestamptz NOT NULL,
    decoded_bytes integer NOT NULL DEFAULT 0 CHECK (decoded_bytes BETWEEN 0 AND 65536),
    completed_at timestamptz,
    error_code varchar(64),
    UNIQUE(check_id,ordinal),
    CHECK (not_after > dispatched_at)
);
CREATE INDEX personal_model_check_request_expiry ON personal_model_check_request(not_after) WHERE status='DISPATCHED';
ALTER TABLE personal_model_check ADD COLUMN reason varchar(64);
