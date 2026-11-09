ALTER TABLE workspace_operation ADD COLUMN last_attempt_at timestamptz NOT NULL DEFAULT '-infinity';
DROP INDEX workspace_pending_operations;
CREATE INDEX workspace_pending_operations ON workspace_operation(last_attempt_at, created_at, id)
    WHERE state IN ('PENDING', 'PENDING_RECONCILIATION');
