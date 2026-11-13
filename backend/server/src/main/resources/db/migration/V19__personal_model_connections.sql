CREATE TABLE personal_model_connection (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL,
    name varchar(128) NOT NULL,
    base_url varchar(2048) NOT NULL,
    protocol varchar(32) NOT NULL CHECK (protocol IN ('OPENAI_CHAT','OPENAI_RESPONSES','ANTHROPIC')),
    preset varchar(32) NOT NULL CHECK (preset IN ('DEEPSEEK','GLM','KIMI','OPENAI','CUSTOM')),
    credential_key_id varchar(64),
    credential_nonce varchar(32),
    credential_ciphertext text,
    version bigint NOT NULL CHECK (version > 0),
    configuration_version bigint NOT NULL CHECK (configuration_version > 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    deleted_at timestamptz,
    CHECK (deleted_at IS NOT NULL OR (credential_key_id IS NOT NULL AND credential_nonce IS NOT NULL AND credential_ciphertext IS NOT NULL))
);
CREATE UNIQUE INDEX personal_model_connection_name ON personal_model_connection(user_id, lower(name)) WHERE deleted_at IS NULL;
CREATE INDEX personal_model_connection_owner ON personal_model_connection(user_id,id) WHERE deleted_at IS NULL;

CREATE TABLE personal_model_configuration (
    id uuid PRIMARY KEY,
    connection_id uuid NOT NULL,
    user_id uuid NOT NULL,
    name varchar(128) NOT NULL,
    model_id varchar(256) NOT NULL,
    context_window integer NOT NULL CHECK (context_window > 0),
    max_output_tokens integer NOT NULL CHECK (max_output_tokens > 0 AND max_output_tokens < context_window),
    thinking_mode varchar(16) NOT NULL CHECK (thinking_mode IN ('OFF','ON')),
    reasoning_effort varchar(32),
    thinking_budget_tokens integer,
    temperature double precision,
    version bigint NOT NULL CHECK (version > 0),
    configuration_version bigint NOT NULL CHECK (configuration_version > 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    deleted_at timestamptz
);
CREATE UNIQUE INDEX personal_model_configuration_name ON personal_model_configuration(connection_id,lower(name)) WHERE deleted_at IS NULL;
CREATE INDEX personal_model_configuration_owner ON personal_model_configuration(user_id,connection_id,id) WHERE deleted_at IS NULL;

CREATE TABLE personal_model_command (
    user_id uuid NOT NULL,
    kind varchar(32) NOT NULL,
    scope varchar(64) NOT NULL,
    request_key varchar(128) NOT NULL,
    fingerprint varchar(64) NOT NULL,
    response jsonb,
    completed_at timestamptz NOT NULL,
    PRIMARY KEY (user_id,kind,scope,request_key)
);
CREATE INDEX personal_model_command_expiry ON personal_model_command(completed_at) WHERE response IS NOT NULL;
