CREATE INDEX personal_model_check_pending ON personal_model_check(started_at,id) WHERE status IN ('QUEUED','RUNNING');
CREATE INDEX personal_model_check_latest ON personal_model_check(user_id,model_configuration_id,started_at DESC,id DESC);
