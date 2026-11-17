ALTER TABLE copilot_task DROP CONSTRAINT copilot_task_lane_check;
ALTER TABLE copilot_task ADD CONSTRAINT copilot_task_lane_check CHECK (lane IN ('MAIN','BTW','CHILD','EXPLORE'));
DROP INDEX one_active_copilot_task;
CREATE UNIQUE INDEX one_active_copilot_task ON copilot_task(exercise_id,lane)
    WHERE lane <> 'EXPLORE' AND state IN ('QUEUED','RUNNING','PAUSED','WAITING_APPROVAL','WAITING_WRITE','WAITING_TEST','WAITING_COMMAND','WAITING_CHILD','STOPPING');
CREATE UNIQUE INDEX one_active_explore_conversation ON copilot_task(conversation_id)
    WHERE lane = 'EXPLORE' AND state IN ('QUEUED','RUNNING','PAUSED','WAITING_APPROVAL','WAITING_WRITE','WAITING_TEST','WAITING_COMMAND','WAITING_CHILD','STOPPING');
ALTER TABLE copilot_delegation ADD COLUMN mode varchar(16) NOT NULL DEFAULT 'EXECUTE' CHECK (mode IN ('EXECUTE','EXPLORE'));
ALTER TABLE copilot_delegation ADD COLUMN explore_slot integer;
ALTER TABLE copilot_delegation ADD CONSTRAINT copilot_delegation_slot_check CHECK (
    (mode = 'EXECUTE' AND explore_slot IS NULL) OR (mode = 'EXPLORE' AND explore_slot IS NOT NULL AND explore_slot BETWEEN 1 AND 3));
DROP INDEX one_waiting_copilot_delegation;
CREATE UNIQUE INDEX one_waiting_copilot_delegation ON copilot_delegation(parent_task_id) WHERE state='WAITING' AND mode='EXECUTE';
CREATE UNIQUE INDEX three_waiting_explore_delegations ON copilot_delegation(parent_task_id,explore_slot) WHERE state='WAITING' AND mode='EXPLORE';
