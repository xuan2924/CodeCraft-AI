CREATE TABLE workspace_command (
    id uuid PRIMARY KEY,
    exercise_id uuid NOT NULL,
    user_id uuid NOT NULL,
    workspace_instance_id uuid NOT NULL,
    execution_instance_id uuid NOT NULL UNIQUE,
    session_hash varchar(64) NOT NULL,
    actor_auth_version bigint NOT NULL,
    request_key varchar(128) NOT NULL,
    fingerprint varchar(64) NOT NULL,
    command_text text NOT NULL CHECK (octet_length(command_text) BETWEEN 1 AND 4096),
    working_directory varchar(1024) NOT NULL,
    revision bigint NOT NULL CHECK (revision >= 0),
    gate_epoch bigint NOT NULL CHECK (gate_epoch >= 0),
    not_after timestamptz NOT NULL,
    host_epoch varchar(128),
    state varchar(32) NOT NULL DEFAULT 'ACCEPTED' CHECK (state IN ('ACCEPTED','STARTING','RUNNING','STOPPING','EXITED','TIMED_OUT','STOPPED','ENVIRONMENT_ERROR','INTERRUPTED')),
    dispatched boolean NOT NULL DEFAULT false,
    stop_requested boolean NOT NULL DEFAULT false,
    quiescent boolean NOT NULL DEFAULT false,
    synchronization varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (synchronization IN ('PENDING','SYNCED','UNCHANGED','CONFLICT','REJECTED')),
    snapshot jsonb,
    state_version bigint NOT NULL DEFAULT 0 CHECK (state_version >= 0),
    pruning boolean NOT NULL DEFAULT false,
    pruned boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL,
    completed_at timestamptz,
    last_checked_at timestamptz NOT NULL DEFAULT '-infinity',
    UNIQUE(exercise_id, request_key)
);
CREATE INDEX workspace_command_discovery ON workspace_command(last_checked_at, created_at, id) WHERE NOT pruned;
CREATE INDEX workspace_command_history ON workspace_command(exercise_id, created_at DESC, id DESC);
CREATE UNIQUE INDEX workspace_command_active ON workspace_command(exercise_id)
    WHERE NOT quiescent OR synchronization='PENDING';
