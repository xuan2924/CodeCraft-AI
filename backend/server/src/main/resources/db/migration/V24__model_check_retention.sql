ALTER TABLE personal_model_check ADD COLUMN completed_at timestamptz;
-- Legacy terminal groups lack an exact completion instant. Retain conservatively
-- for another full retention period rather than deriving it from request start.
UPDATE personal_model_check SET completed_at=CURRENT_TIMESTAMP
WHERE status NOT IN ('QUEUED','RUNNING');
CREATE INDEX personal_model_check_retention ON personal_model_check(completed_at,id)
WHERE completed_at IS NOT NULL;
CREATE INDEX personal_model_check_reuse_basic ON personal_model_check_reuse(basic_request_id)
WHERE basic_request_id IS NOT NULL;
CREATE INDEX personal_model_check_reuse_tool ON personal_model_check_reuse(tool_request_id)
WHERE tool_request_id IS NOT NULL;
