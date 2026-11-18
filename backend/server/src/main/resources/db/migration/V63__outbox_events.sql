-- Delivery is at-least-once. Business state and enqueue commit together, including all existing producers.
CREATE TABLE outbox_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    kind text NOT NULL CHECK (kind IN ('TEST','COMMAND','REPORT')),
    aggregate_id uuid NOT NULL,
    exercise_id uuid NOT NULL,
    generation bigint NOT NULL DEFAULT 0,
    attempt integer NOT NULL DEFAULT 0,
    lane text NOT NULL CHECK (lane IN ('interactive','slow')),
    state text NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING','LEASED','SENT','DONE','DEAD')),
    dead_published boolean NOT NULL DEFAULT false,
    publish_attempts integer NOT NULL DEFAULT 0,
    failures integer NOT NULL DEFAULT 0,
    available_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    lease_token uuid,
    lease_until timestamptz,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(kind, aggregate_id, generation, attempt)
);
CREATE INDEX outbox_ready ON outbox_events(available_at, id) WHERE state IN ('PENDING','LEASED','SENT');
CREATE INDEX outbox_exercise ON outbox_events(exercise_id);
CREATE TABLE outbox_consumption (
    event_id uuid PRIMARY KEY,
    state text NOT NULL CHECK (state IN ('PROCESSING','DONE','DEAD')),
    token uuid NOT NULL,
    lease_until timestamptz NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE outbox_dead_letter (
    event_id uuid PRIMARY KEY,
    reason text NOT NULL,
    failures integer NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE FUNCTION enqueue_workspace_task() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME = 'workspace_report' THEN
        IF NEW.state = 'QUEUED' THEN
            INSERT INTO outbox_events(kind, aggregate_id, exercise_id, generation, attempt, lane)
            VALUES ('REPORT', NEW.exercise_id, NEW.exercise_id, NEW.generation, NEW.attempts, 'slow')
            ON CONFLICT (kind, aggregate_id, generation, attempt) DO NOTHING;
        END IF;
    ELSE
        INSERT INTO outbox_events(kind, aggregate_id, exercise_id, lane)
        VALUES (CASE WHEN TG_TABLE_NAME = 'workspace_test_task' THEN 'TEST' ELSE 'COMMAND' END, NEW.id, NEW.exercise_id, 'interactive');
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER enqueue_test AFTER INSERT ON workspace_test_task FOR EACH ROW EXECUTE FUNCTION enqueue_workspace_task();
CREATE TRIGGER enqueue_command AFTER INSERT ON workspace_command FOR EACH ROW EXECUTE FUNCTION enqueue_workspace_task();
CREATE TRIGGER enqueue_report AFTER INSERT OR UPDATE OF state ON workspace_report FOR EACH ROW EXECUTE FUNCTION enqueue_workspace_task();
-- A report that never reached a paid dispatch may expose a terminal failure for explicit user retry.
-- RUNNING/UNKNOWN reports and execution receipts are never reset by delivery failure.
CREATE FUNCTION retire_dead_report() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.state='DEAD' AND NEW.kind='REPORT' THEN
        WITH failed AS (
            UPDATE workspace_report SET state='FAILED',reason='OUTBOX_DELIVERY_EXHAUSTED',deadline_at=NULL,updated_at=clock_timestamp()
            WHERE exercise_id=NEW.aggregate_id AND generation=NEW.generation AND attempts=NEW.attempt
              AND state IN ('QUEUED','PREPARING') RETURNING operation_id
        ) UPDATE workspace_operation SET state='REJECTED' WHERE id IN (SELECT operation_id FROM failed);
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER outbox_report_dead AFTER UPDATE OF state ON outbox_events FOR EACH ROW EXECUTE FUNCTION retire_dead_report();

-- Bootstrap pending work on upgrade, never replay already-dispatched effects.
INSERT INTO outbox_events(kind,aggregate_id,exercise_id,lane)
SELECT 'TEST',id,exercise_id,'interactive' FROM workspace_test_task WHERE NOT dispatched AND NOT quiescent;
INSERT INTO outbox_events(kind,aggregate_id,exercise_id,lane)
SELECT 'COMMAND',id,exercise_id,'interactive' FROM workspace_command WHERE NOT dispatched AND NOT quiescent;
INSERT INTO outbox_events(kind,aggregate_id,exercise_id,generation,attempt,lane)
SELECT 'REPORT',exercise_id,exercise_id,generation,attempts,'slow' FROM workspace_report WHERE state='QUEUED';
