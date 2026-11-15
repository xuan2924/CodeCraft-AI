CREATE SEQUENCE copilot_task_admission_sequence;
ALTER TABLE copilot_task ADD COLUMN admission_sequence bigint;
WITH ordered AS (SELECT id,row_number() OVER (ORDER BY created_at,id) AS ordinal FROM copilot_task)
UPDATE copilot_task t SET admission_sequence=o.ordinal FROM ordered o WHERE t.id=o.id;
SELECT setval('copilot_task_admission_sequence',COALESCE((SELECT max(admission_sequence) FROM copilot_task),0)+1,false);
ALTER TABLE copilot_task ALTER COLUMN admission_sequence SET DEFAULT nextval('copilot_task_admission_sequence'), ALTER COLUMN admission_sequence SET NOT NULL;
ALTER SEQUENCE copilot_task_admission_sequence OWNED BY copilot_task.admission_sequence;
CREATE UNIQUE INDEX copilot_task_admission_order ON copilot_task(admission_sequence);
CREATE INDEX copilot_task_conversation_order ON copilot_task(conversation_id,admission_sequence DESC);

CREATE TABLE copilot_followup (
    id uuid PRIMARY KEY,
    sequence bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    exercise_id uuid NOT NULL,
    conversation_id uuid NOT NULL,
    user_id uuid NOT NULL,
    session_hash varchar(128) NOT NULL,
    control_hash varchar(128) NOT NULL,
    request_key varchar(128) NOT NULL,
    text text NOT NULL,
    anchor_task_id uuid NOT NULL,
    anchor_sequence bigint NOT NULL,
    resume_after uuid,
    state varchar(16) NOT NULL CHECK (state IN ('WAITING','PAUSED','DISPATCHED','CANCELLED')),
    reason varchar(128),
    task_id uuid,
    created_at timestamptz NOT NULL,
    completed_at timestamptz,
    generation bigint NOT NULL DEFAULT 0,
    UNIQUE (exercise_id, request_key),
    CHECK ((state='DISPATCHED') = (task_id IS NOT NULL))
);
CREATE INDEX copilot_followup_conversation ON copilot_followup(conversation_id,sequence);
CREATE INDEX copilot_followup_pending ON copilot_followup(state,sequence);
CREATE INDEX copilot_followup_exercise ON copilot_followup(exercise_id);
CREATE TABLE copilot_followup_resume (
    id uuid PRIMARY KEY,
    exercise_id uuid NOT NULL,
    conversation_id uuid NOT NULL,
    request_key varchar(128) NOT NULL,
    created_at timestamptz NOT NULL,
    UNIQUE (exercise_id,request_key)
);
