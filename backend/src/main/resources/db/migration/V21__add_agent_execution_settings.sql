-- Nullable Agent overrides inherit deployment defaults when a new task is created.
ALTER TABLE agent_app
    ADD COLUMN decision_max_output_tokens INTEGER,
    ADD COLUMN final_max_output_tokens INTEGER,
    ADD COLUMN decision_response_format VARCHAR(32),
    ADD COLUMN thinking_mode VARCHAR(32),
    ADD COLUMN model_call_timeout_seconds INTEGER,
    ADD CONSTRAINT ck_agent_app_decision_max_output_tokens
        CHECK (decision_max_output_tokens IS NULL OR decision_max_output_tokens BETWEEN 1 AND 16384),
    ADD CONSTRAINT ck_agent_app_final_max_output_tokens
        CHECK (final_max_output_tokens IS NULL OR (
            final_max_output_tokens BETWEEN 1 AND 16384
            AND final_max_output_tokens >= LEAST(2048, GREATEST(1, max_tokens / 4)))),
    ADD CONSTRAINT ck_agent_app_decision_response_format
        CHECK (decision_response_format IS NULL OR decision_response_format IN ('PROMPT_ONLY', 'JSON_OBJECT', 'JSON_SCHEMA')),
    ADD CONSTRAINT ck_agent_app_thinking_mode
        CHECK (thinking_mode IS NULL OR thinking_mode IN ('PROVIDER_DEFAULT', 'DISABLED')),
    ADD CONSTRAINT ck_agent_app_model_call_timeout_seconds
        CHECK (model_call_timeout_seconds IS NULL OR model_call_timeout_seconds BETWEEN 1 AND 600);
