-- V37 / M4B: establish the owner-scoped Agent execution dependencies that must exist
-- before AgentTask, Trace, or the V36 engine can be connected to persistent execution.
-- V1-V16 stay immutable.

ALTER TABLE agent_app
    ADD CONSTRAINT uk_agent_app_id_user UNIQUE (id, user_id);

ALTER TABLE knowledge_chunk
    ADD COLUMN chunk_strategy_version VARCHAR(64);

UPDATE knowledge_chunk
SET chunk_strategy_version = 'structured-token-v1'
WHERE chunk_strategy_version IS NULL;

ALTER TABLE knowledge_chunk
    ALTER COLUMN chunk_strategy_version SET NOT NULL,
    ADD CONSTRAINT ck_chunk_strategy_version_not_blank
        CHECK (char_length(btrim(chunk_strategy_version)) > 0);

CREATE TABLE agent_knowledge_binding (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    agent_id BIGINT NOT NULL,
    knowledge_base_id BIGINT NOT NULL,
    priority INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_agent_knowledge_binding_agent_owner
        FOREIGN KEY (agent_id, user_id) REFERENCES agent_app (id, user_id),
    CONSTRAINT fk_agent_knowledge_binding_kb_owner
        FOREIGN KEY (knowledge_base_id, user_id) REFERENCES knowledge_base (id, user_id),
    CONSTRAINT uk_agent_knowledge_binding_agent_kb
        UNIQUE (agent_id, knowledge_base_id),
    CONSTRAINT ck_agent_knowledge_binding_priority
        CHECK (priority >= 0)
);

CREATE INDEX idx_agent_knowledge_binding_kb
    ON agent_knowledge_binding (knowledge_base_id, agent_id);

CREATE TABLE agent_tool_binding (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    agent_id BIGINT NOT NULL,
    tool_id BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    priority INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_agent_tool_binding_agent_owner
        FOREIGN KEY (agent_id, user_id) REFERENCES agent_app (id, user_id),
    CONSTRAINT fk_agent_tool_binding_tool
        FOREIGN KEY (tool_id) REFERENCES tool_definition (id),
    CONSTRAINT uk_agent_tool_binding_agent_tool
        UNIQUE (agent_id, tool_id),
    CONSTRAINT ck_agent_tool_binding_priority
        CHECK (priority >= 0)
);

CREATE INDEX idx_agent_tool_binding_tool
    ON agent_tool_binding (tool_id, agent_id);
