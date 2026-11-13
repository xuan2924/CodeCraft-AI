ALTER TABLE workspace_deletion ADD COLUMN last_manual_retry_at timestamptz;
CREATE INDEX workspace_operation_owner_meter ON workspace_operation(exercise_id);
CREATE INDEX workspace_transition_owner_meter ON workspace_transition(exercise_id);
CREATE INDEX workspace_exercise_owner_history ON workspace_exercise(user_id, state);
ALTER TABLE workspace_test_stop ADD COLUMN owner_recovery boolean NOT NULL DEFAULT false;
CREATE INDEX workspace_stop_intent_lookup ON workspace_test_stop(task_id, state);
CREATE TABLE workspace_storage_budget (
    exercise_id uuid PRIMARY KEY,
    meter_epoch varchar(36),
    sequence bigint NOT NULL DEFAULT 0 CHECK (sequence >= 0),
    bytes bigint NOT NULL DEFAULT 0 CHECK (bytes >= 0),
    checked_at timestamptz NOT NULL DEFAULT 'epoch',
    last_attempt_at timestamptz NOT NULL DEFAULT 'epoch'
);
CREATE TABLE workspace_storage_instance (
    workspace_instance_id uuid PRIMARY KEY,
    exercise_id uuid NOT NULL
);
CREATE INDEX workspace_storage_instance_owner ON workspace_storage_instance(exercise_id);
INSERT INTO workspace_storage_instance(workspace_instance_id, exercise_id)
    SELECT workspace_instance_id, id FROM workspace_exercise
    UNION SELECT workspace_instance_id, exercise_id FROM workspace_operation;
CREATE TABLE workspace_storage_execution (
    execution_instance_id uuid PRIMARY KEY,
    exercise_id uuid NOT NULL,
    workspace_instance_id uuid NOT NULL,
    task_id uuid NOT NULL
);
CREATE INDEX workspace_storage_execution_owner ON workspace_storage_execution(exercise_id);
INSERT INTO workspace_storage_execution(execution_instance_id, exercise_id, workspace_instance_id, task_id)
    SELECT execution_instance_id, exercise_id, workspace_instance_id, id FROM workspace_test_task;
CREATE TABLE workspace_storage_reservation (
    operation_id uuid PRIMARY KEY,
    exercise_id uuid NOT NULL,
    growth_bytes bigint NOT NULL CHECK (growth_bytes > 0),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX workspace_storage_reservation_owner ON workspace_storage_reservation(exercise_id);
-- Legacy unresolved work remains charged until its actual side effects converge.
INSERT INTO workspace_storage_budget(exercise_id) SELECT id FROM workspace_exercise;
INSERT INTO workspace_storage_reservation(operation_id, exercise_id, growth_bytes)
    SELECT id, exercise_id, CASE WHEN kind IN ('START_TEST', 'FINAL_TEST') THEN 100663296
        WHEN kind = 'FILE_BATCH' THEN 83886080 WHEN kind IN ('PREPARE', 'PREPARATION_RETRY') THEN 33554432
        ELSE 65536 END
    FROM workspace_operation WHERE state IN ('PENDING', 'PENDING_RECONCILIATION');
