CREATE TABLE personal_model_check_reuse (
    check_id uuid PRIMARY KEY,
    basic_request_id uuid,
    tool_request_id uuid,
    CHECK (basic_request_id IS NOT NULL OR tool_request_id IS NOT NULL)
);
CREATE INDEX personal_model_check_configuration ON personal_model_check(user_id,connection_id,model_configuration_id,connection_configuration_version,model_configuration_version);
CREATE INDEX personal_model_check_request_success ON personal_model_check_request(check_id,ordinal,completed_at DESC) WHERE status='SUCCEEDED';
