ALTER TABLE workspace_exercise
    ADD COLUMN preparation_failure_reason varchar(64),
    ADD COLUMN end_reason varchar(64),
    ADD COLUMN cleanup_state varchar(16) CHECK (cleanup_state IN ('PENDING', 'SUCCEEDED', 'FAILED'));
