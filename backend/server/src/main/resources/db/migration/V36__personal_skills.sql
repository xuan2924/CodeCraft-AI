CREATE TABLE personal_skill (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL,
    name varchar(64) NOT NULL,
    current_version_id uuid,
    version bigint NOT NULL CHECK (version > 0),
    enabled boolean NOT NULL,
    deleted boolean NOT NULL DEFAULT false,
    updated_at timestamptz NOT NULL,
    CHECK (deleted OR current_version_id IS NOT NULL)
);
CREATE UNIQUE INDEX personal_skill_owner_name ON personal_skill(user_id,name) WHERE NOT deleted;
CREATE INDEX personal_skill_owner ON personal_skill(user_id,id);
CREATE TABLE personal_skill_version (
    id uuid PRIMARY KEY,
    skill_id uuid NOT NULL,
    user_id uuid NOT NULL,
    name varchar(64) NOT NULL,
    description text NOT NULL,
    files_payload text NOT NULL,
    content_bytes bigint NOT NULL CHECK (content_bytes > 0),
    content_hash varchar(64) NOT NULL,
    created_at timestamptz NOT NULL
);
CREATE INDEX personal_skill_version_owner ON personal_skill_version(user_id,skill_id);
-- Explicit references are also the protection against reclaiming a version after original deletion.
CREATE TABLE copilot_skill_snapshot (
    task_id uuid NOT NULL,
    exercise_id uuid NOT NULL,
    user_id uuid NOT NULL,
    skill_id uuid NOT NULL,
    version_id uuid NOT NULL,
    PRIMARY KEY(task_id,skill_id)
);
CREATE INDEX copilot_skill_snapshot_version ON copilot_skill_snapshot(version_id);
CREATE INDEX copilot_skill_snapshot_exercise ON copilot_skill_snapshot(exercise_id);
CREATE TABLE personal_skill_command (
    user_id uuid NOT NULL,
    request_key varchar(128) NOT NULL,
    fingerprint varchar(64) NOT NULL,
    response_payload text NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY(user_id,request_key)
);
