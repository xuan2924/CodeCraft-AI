-- Java's serialized workspace_execution_admission enforces the configured budget;
-- the obsolete global single-test index would silently defeat multi-slot/multi-node execution.
DROP INDEX one_host_test;

CREATE TABLE sandbox_node (
    id text PRIMARY KEY,
    endpoint text NOT NULL UNIQUE,
    storage_id text UNIQUE,
    host_epoch text,
    ready boolean NOT NULL DEFAULT false,
    active_slots integer NOT NULL DEFAULT 0 CHECK (active_slots>=0),
    max_slots integer NOT NULL DEFAULT 1 CHECK (max_slots BETWEEN 1 AND 64),
    healthy_until timestamptz NOT NULL DEFAULT '-infinity',
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE sandbox_binding (
    exercise_id uuid PRIMARY KEY,
    node_id text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
-- The configured default endpoint is registered by Java, not embedded in migration.
INSERT INTO sandbox_binding(exercise_id,node_id) SELECT id,'default' FROM workspace_exercise;
CREATE INDEX sandbox_binding_node ON sandbox_binding(node_id);
