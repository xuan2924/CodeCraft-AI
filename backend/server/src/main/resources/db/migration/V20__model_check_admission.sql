-- User authorization locks serialize admission and configuration mutation.
-- The partial unique index is an additional guard, not a substitute for that lock.
CREATE TABLE personal_model_check (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL,
    connection_id uuid NOT NULL,
    model_configuration_id uuid NOT NULL,
    connection_configuration_version bigint NOT NULL CHECK (connection_configuration_version > 0),
    model_configuration_version bigint NOT NULL CHECK (model_configuration_version > 0),
    status varchar(24) NOT NULL CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','INTERRUPTED','SUPERSEDED')),
    started_at timestamptz NOT NULL
);
CREATE UNIQUE INDEX personal_model_check_active_user ON personal_model_check(user_id) WHERE status IN ('QUEUED','RUNNING');
CREATE INDEX personal_model_check_user_starts ON personal_model_check(user_id,started_at);
