-- A final result may reuse an ordinary task without becoming a system-dispatch attempt.
CREATE INDEX workspace_finalization_result_task ON workspace_finalization(result_task_id)
    WHERE result_task_id IS NOT NULL;
