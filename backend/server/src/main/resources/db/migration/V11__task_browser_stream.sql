ALTER TABLE workspace_test_task ADD COLUMN go_stream_id text NOT NULL DEFAULT '';
ALTER TABLE workspace_test_task ADD COLUMN go_sequence bigint NOT NULL DEFAULT 0 CHECK (go_sequence >= 0);

-- Browser cursors are deliberately independent of Go stream IDs and counters.
CREATE TABLE workspace_task_stream (
    task_id uuid PRIMARY KEY REFERENCES workspace_test_task(id),
    stream_id uuid NOT NULL UNIQUE,
    sequence bigint NOT NULL DEFAULT 0 CHECK (sequence >= 0),
    first_sequence bigint NOT NULL DEFAULT 1 CHECK (first_sequence > 0)
);
CREATE TABLE workspace_task_event (
    task_id uuid NOT NULL REFERENCES workspace_task_stream(task_id),
    sequence bigint NOT NULL,
    type text NOT NULL,
    payload text NOT NULL,
    bytes integer NOT NULL CHECK (bytes > 0 AND bytes <= 65536),
    PRIMARY KEY (task_id, sequence)
);
-- Existing snapshots remain readable; a first observation publishes a checkpoint.
INSERT INTO workspace_task_stream(task_id, stream_id) SELECT id, gen_random_uuid() FROM workspace_test_task;
