CREATE TABLE problem_version (
    id uuid PRIMARY KEY,
    problem_id varchar(64) NOT NULL,
    version varchar(32) NOT NULL,
    package_sha256 char(64) NOT NULL CHECK (package_sha256 ~ '^[0-9a-f]{64}$'),
    execution_image_digest varchar(71) NOT NULL CHECK (execution_image_digest ~ '^sha256:[0-9a-f]{64}$'),
    platform varchar(32) NOT NULL CHECK (platform = 'linux/amd64'),
    artifact jsonb NOT NULL,
    published_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (problem_id, version)
);
