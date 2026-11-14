CREATE TABLE copilot_approval_mode (
    exercise_id uuid PRIMARY KEY,
    mode varchar(8) NOT NULL CHECK (mode IN ('ASK','AUTO')),
    version bigint NOT NULL CHECK (version > 0),
    updated_at timestamptz NOT NULL
);
CREATE TABLE copilot_approval_change (
    id uuid PRIMARY KEY,
    exercise_id uuid NOT NULL,
    actor_id uuid NOT NULL,
    auth_version bigint NOT NULL,
    previous_mode varchar(8) NOT NULL CHECK (previous_mode IN ('ASK','AUTO')),
    mode varchar(8) NOT NULL CHECK (mode IN ('ASK','AUTO')),
    version bigint NOT NULL CHECK (version > 0),
    request_key varchar(128) NOT NULL,
    fingerprint varchar(64) NOT NULL,
    created_at timestamptz NOT NULL,
    UNIQUE(exercise_id, version),
    UNIQUE(exercise_id, request_key)
);
ALTER TABLE copilot_proposal ADD COLUMN mode_version bigint NOT NULL DEFAULT 0 CHECK (mode_version >= 0);
ALTER TABLE copilot_proposal ADD CONSTRAINT copilot_proposal_auto_mode_version CHECK (mode = 'ASK' OR mode_version > 0);
