-- The first M4 root resource. This migration creates only agent_app metadata;
-- prompt versions, knowledge/tool bindings, tasks, runtime logs, and execution stay later slices.
--
-- Keep this migration immutable after it has been applied. Future schema changes must
-- use a new, higher-numbered Flyway migration.

CREATE TABLE agent_app (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    description TEXT,
    system_prompt TEXT NOT NULL,
    model_provider VARCHAR(64) NOT NULL,
    model_name VARCHAR(128) NOT NULL,
    temperature NUMERIC(4,3) NOT NULL DEFAULT 0.2,
    top_p NUMERIC(4,3) NOT NULL DEFAULT 0.8,
    max_steps INT NOT NULL DEFAULT 6,
    max_tool_calls INT NOT NULL DEFAULT 4,
    max_tokens INT NOT NULL DEFAULT 8000,
    timeout_seconds INT NOT NULL DEFAULT 120,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    config JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ,

    CONSTRAINT fk_agent_app_user
        FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT ck_agent_app_name_not_blank
        CHECK (char_length(btrim(name)) > 0),
    CONSTRAINT ck_agent_app_description_length
        CHECK (description IS NULL OR char_length(description) <= 4000),
    CONSTRAINT ck_agent_app_system_prompt
        CHECK (char_length(btrim(system_prompt)) > 0 AND char_length(system_prompt) <= 20000),
    CONSTRAINT ck_agent_app_model_provider_not_blank
        CHECK (char_length(btrim(model_provider)) > 0),
    CONSTRAINT ck_agent_app_model_name_not_blank
        CHECK (char_length(btrim(model_name)) > 0),
    CONSTRAINT ck_agent_app_temperature
        CHECK (temperature BETWEEN 0 AND 2),
    CONSTRAINT ck_agent_app_top_p
        CHECK (top_p > 0 AND top_p <= 1),
    CONSTRAINT ck_agent_app_max_steps
        CHECK (max_steps BETWEEN 1 AND 20),
    CONSTRAINT ck_agent_app_max_tool_calls
        CHECK (max_tool_calls BETWEEN 0 AND 20 AND max_tool_calls <= max_steps),
    CONSTRAINT ck_agent_app_max_tokens
        CHECK (max_tokens BETWEEN 256 AND 100000),
    CONSTRAINT ck_agent_app_timeout_seconds
        CHECK (timeout_seconds BETWEEN 1 AND 600),
    CONSTRAINT ck_agent_app_status
        CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_agent_app_config_object
        CHECK (jsonb_typeof(config) = 'object')
);

-- The only V30 list path is current-owner, non-deleted, newest-first pagination.
CREATE INDEX idx_agent_app_user_created
    ON agent_app (user_id, created_at DESC, id DESC)
    WHERE deleted_at IS NULL;
