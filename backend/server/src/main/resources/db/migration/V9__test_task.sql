CREATE TABLE workspace_test_task (
    id uuid PRIMARY KEY REFERENCES workspace_operation(id),
    execution_instance_id uuid NOT NULL UNIQUE,
    exercise_id uuid NOT NULL REFERENCES workspace_exercise(id),
    workspace_instance_id uuid NOT NULL,
    revision bigint NOT NULL CHECK (revision >= 0),
    gate_epoch bigint NOT NULL CHECK (gate_epoch >= 0),
    not_after timestamptz NOT NULL,
    target varchar(32) NOT NULL CHECK (target = 'base'),
    state varchar(32) NOT NULL DEFAULT 'ACCEPTED',
    dispatched boolean NOT NULL DEFAULT false,
    quiescent boolean NOT NULL DEFAULT false,
    snapshot jsonb,
    last_attempt_at timestamptz NOT NULL DEFAULT '-infinity',
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE UNIQUE INDEX one_test_per_exercise ON workspace_test_task(exercise_id) WHERE NOT quiescent;
-- Published first-problem runtime.globalConcurrency is 1; refuse, never queue.
CREATE TABLE workspace_execution_admission (id integer PRIMARY KEY CHECK (id = 1));
INSERT INTO workspace_execution_admission VALUES (1);
CREATE UNIQUE INDEX one_host_test ON workspace_test_task((1)) WHERE NOT quiescent;
CREATE INDEX pending_test_observation ON workspace_test_task(last_attempt_at, id);
