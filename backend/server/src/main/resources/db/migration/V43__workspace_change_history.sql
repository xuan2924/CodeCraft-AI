CREATE TABLE workspace_change (
    id uuid PRIMARY KEY,
    exercise_id uuid NOT NULL,
    workspace_instance_id uuid NOT NULL,
    source varchar(16) NOT NULL CHECK (source IN ('MANUAL','AI_PATCH','BASH','UNDO','ROLLBACK')),
    base_revision bigint NOT NULL CHECK (base_revision >= 0),
    result_revision bigint NOT NULL CHECK (result_revision = base_revision + 1),
    task_id uuid,
    conversation_id uuid,
    origin_change_id uuid,
    retained boolean NOT NULL DEFAULT true,
    recorded_at timestamptz NOT NULL,
    UNIQUE(exercise_id, result_revision)
);
CREATE INDEX workspace_change_history ON workspace_change(exercise_id, result_revision DESC);
CREATE INDEX workspace_change_retained ON workspace_change(exercise_id, result_revision DESC) WHERE retained;

-- Restore requests remain FILE_BATCH operations; this records the exact user
-- intent independently of its computed payload, including no-op receipts.
CREATE TABLE workspace_restore (
    operation_id uuid PRIMARY KEY,
    exercise_id uuid NOT NULL,
    change_id uuid NOT NULL,
    mode varchar(16) NOT NULL CHECK (mode IN ('UNDO','ROLLBACK')),
    request_fingerprint varchar(64) NOT NULL,
    preview_fingerprint varchar(64) NOT NULL,
    expected_revision bigint NOT NULL CHECK (expected_revision >= 0),
    target_revision bigint CHECK (target_revision >= 0),
    CHECK ((mode = 'UNDO' AND target_revision IS NULL) OR (mode = 'ROLLBACK' AND target_revision IS NOT NULL))
);
CREATE INDEX workspace_restore_owner ON workspace_restore(exercise_id);

-- Previous releases did not retain arbitrary code versions. Backfill facts,
-- never claim that an already-collected tree can be restored.
INSERT INTO workspace_change(id, exercise_id, workspace_instance_id, source, base_revision, result_revision,
    task_id, conversation_id, retained, recorded_at)
SELECT o.id, o.exercise_id, o.workspace_instance_id,
    CASE WHEN p.id IS NULL THEN 'MANUAL' ELSE 'AI_PATCH' END, m.revision - 1, m.revision,
    t.id, t.conversation_id, false, o.created_at
FROM workspace_mutation m JOIN workspace_operation o ON o.id = m.operation_id
LEFT JOIN copilot_proposal p ON p.operation_id = o.id
LEFT JOIN copilot_task t ON t.id = p.task_id
WHERE m.changed AND m.revision > 0
    AND NOT EXISTS (SELECT 1 FROM workspace_deletion d WHERE d.exercise_id = o.exercise_id);

INSERT INTO workspace_change(id, exercise_id, workspace_instance_id, source, base_revision, result_revision,
    task_id, conversation_id, retained, recorded_at)
SELECT c.id, c.exercise_id, c.workspace_instance_id, 'BASH', c.revision, c.revision + 1,
    t.id, t.conversation_id, false, COALESCE(c.completed_at, c.created_at)
FROM workspace_command c
LEFT JOIN copilot_command p ON p.command_task_id = c.id
LEFT JOIN copilot_task t ON t.id = p.task_id
WHERE c.synchronization = 'SYNCED'
    AND NOT EXISTS (SELECT 1 FROM workspace_deletion d WHERE d.exercise_id = c.exercise_id);
