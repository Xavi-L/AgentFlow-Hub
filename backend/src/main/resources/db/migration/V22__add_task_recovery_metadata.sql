-- V0.2-A: recovery is an observed local settlement, never an execution retry.
-- Historical rows remain NULL: absence does not certify complete execution facts.
ALTER TABLE agent_task
    ADD COLUMN recovery_metadata JSONB,
    ADD CONSTRAINT ck_agent_task_recovery_metadata_object
        CHECK (recovery_metadata IS NULL OR jsonb_typeof(recovery_metadata) = 'object');

-- A recovery end timestamp is a local observation, not a measured execution duration.
-- Preserve the existing normal lifecycle constraints; permit unknown latency only for
-- the specifically identified interrupted local records.
ALTER TABLE agent_step DROP CONSTRAINT ck_agent_step_lifecycle;
ALTER TABLE agent_step ADD CONSTRAINT ck_agent_step_lifecycle CHECK (
    (status = 'RUNNING' AND ended_at IS NULL AND latency_ms IS NULL
        AND error_code IS NULL AND error_message IS NULL)
    OR (status IN ('SUCCESS', 'SKIPPED') AND ended_at IS NOT NULL AND latency_ms IS NOT NULL
        AND error_code IS NULL AND error_message IS NULL)
    OR (status = 'FAILED' AND ended_at IS NOT NULL
        AND (latency_ms IS NOT NULL OR error_code = 'TASK_RESTART_INTERRUPTED')
        AND error_code IS NOT NULL AND char_length(btrim(error_code)) > 0
        AND error_message IS NOT NULL AND char_length(btrim(error_message)) > 0)
);

ALTER TABLE tool_call_log DROP CONSTRAINT ck_tool_call_log_terminal_fields;
ALTER TABLE tool_call_log ADD CONSTRAINT ck_tool_call_log_terminal_fields CHECK (
    (status = 'PENDING' AND started_at IS NULL AND finished_at IS NULL AND latency_ms IS NULL
        AND result IS NULL AND error_code IS NULL AND error_message IS NULL)
    OR (status = 'RUNNING' AND started_at IS NOT NULL AND finished_at IS NULL AND latency_ms IS NULL
        AND result IS NULL AND error_code IS NULL AND error_message IS NULL)
    OR (status = 'SUCCESS' AND started_at IS NOT NULL AND finished_at IS NOT NULL AND latency_ms IS NOT NULL
        AND result IS NOT NULL AND jsonb_typeof(result) = 'object'
        AND error_code IS NULL AND error_message IS NULL)
    OR (status IN ('FAILED', 'TIMEOUT', 'REJECTED') AND started_at IS NOT NULL AND finished_at IS NOT NULL
        AND (latency_ms IS NOT NULL OR (status = 'FAILED' AND error_code = 'TASK_RESTART_INTERRUPTED'))
        AND result IS NOT NULL AND jsonb_typeof(result) = 'object'
        AND error_code IS NOT NULL AND char_length(btrim(error_code)) > 0
        AND error_message IS NOT NULL AND char_length(btrim(error_message)) > 0)
);
