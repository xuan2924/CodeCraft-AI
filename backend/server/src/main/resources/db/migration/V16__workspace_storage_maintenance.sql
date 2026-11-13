ALTER TABLE workspace_exercise
    ADD COLUMN maintenance_checked_at timestamptz NOT NULL DEFAULT '-infinity';
CREATE INDEX workspace_maintenance_scan ON workspace_exercise (maintenance_checked_at, id)
    WHERE revision IS NOT NULL;
ALTER TABLE workspace_test_task
    ADD COLUMN history_pruning boolean NOT NULL DEFAULT false,
    ADD COLUMN history_pruned_at timestamptz,
    ADD COLUMN history_cleanup_attempted_at timestamptz NOT NULL DEFAULT 'epoch';
CREATE INDEX workspace_test_retention ON workspace_test_task (exercise_id, history_pruned_at);
