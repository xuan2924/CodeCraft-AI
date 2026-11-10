ALTER TABLE workspace_test_task ADD COLUMN go_state_version bigint NOT NULL DEFAULT 0 CHECK (go_state_version >= 0);

-- Stop intent and its original authority survive lost replies and Java restarts.
CREATE TABLE workspace_test_stop (
    id uuid PRIMARY KEY REFERENCES workspace_operation(id),
    task_id uuid NOT NULL REFERENCES workspace_test_task(id),
    gate_epoch bigint NOT NULL CHECK (gate_epoch >= 0),
    not_after timestamptz NOT NULL,
    state varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'SUCCEEDED', 'REJECTED')),
    last_attempt_at timestamptz NOT NULL DEFAULT '-infinity'
);
CREATE INDEX pending_test_stop ON workspace_test_stop(last_attempt_at, id) WHERE state = 'PENDING';
