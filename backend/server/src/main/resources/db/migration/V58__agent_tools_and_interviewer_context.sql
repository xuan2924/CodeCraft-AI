ALTER TABLE copilot_tool ADD COLUMN outcome_code varchar(32);
UPDATE copilot_tool SET outcome_code='LEGACY_UNCLASSIFIED' WHERE completed_at IS NOT NULL;
ALTER TABLE copilot_task ADD COLUMN planning_revision bigint;
ALTER TABLE copilot_task ADD COLUMN reference_count integer NOT NULL DEFAULT 0 CHECK (reference_count >= 0);

ALTER TABLE workspace_interviewer_task
    ADD COLUMN stage varchar(16) NOT NULL DEFAULT 'BASE' CHECK (stage IN ('BASE','CHALLENGE')),
    ADD COLUMN mode varchar(16) NOT NULL DEFAULT 'CLARIFY' CHECK (mode IN ('CLARIFY','HINT','DEFENSE')),
    ADD COLUMN topic varchar(64) NOT NULL DEFAULT 'general' CHECK (topic ~ '^[A-Za-z0-9_-]{1,64}$'),
    ADD COLUMN hint_level integer NOT NULL DEFAULT 1 CHECK (hint_level BETWEEN 1 AND 3);
