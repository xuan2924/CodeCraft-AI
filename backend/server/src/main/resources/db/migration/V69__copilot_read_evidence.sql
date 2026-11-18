ALTER TABLE copilot_tool ADD COLUMN read_revision bigint;
CREATE INDEX copilot_tool_read_evidence ON copilot_tool(task_id, read_revision) WHERE read_revision IS NOT NULL;
