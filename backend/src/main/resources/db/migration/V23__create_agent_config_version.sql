-- V0.3-A: immutable raw configuration, separately associated with unchanged v1/v2 task snapshots.
-- V1-V22 are immutable. Historical tasks deliberately retain NULL configuration identity.
CREATE TABLE agent_config_version (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    agent_id BIGINT NOT NULL,
    schema_version VARCHAR(64) NOT NULL,
    hash_algorithm_version VARCHAR(64) NOT NULL,
    config_hash VARCHAR(64) NOT NULL,
    config_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_agent_config_version_agent_owner FOREIGN KEY (agent_id, user_id)
        REFERENCES agent_app (id, user_id),
    CONSTRAINT uk_agent_config_version_content UNIQUE (user_id, agent_id, schema_version, config_hash),
    CONSTRAINT uk_agent_config_version_task_identity UNIQUE (id, agent_id, user_id, config_hash),
    CONSTRAINT ck_agent_config_version_schema CHECK (schema_version = 'agent-config-v1'),
    CONSTRAINT ck_agent_config_version_algorithm CHECK (hash_algorithm_version = 'config-canonical-json-v1'),
    CONSTRAINT ck_agent_config_version_hash CHECK (config_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_agent_config_version_object CHECK (jsonb_typeof(config_json) = 'object')
);
CREATE INDEX idx_agent_config_version_owner_created
    ON agent_config_version (user_id, agent_id, created_at DESC, id DESC);

CREATE FUNCTION reject_agent_config_version_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Agent configuration versions are immutable' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER agent_config_version_no_update BEFORE UPDATE ON agent_config_version
    FOR EACH ROW EXECUTE FUNCTION reject_agent_config_version_update();

ALTER TABLE agent_task
    ADD COLUMN config_version_id BIGINT,
    ADD COLUMN config_hash VARCHAR(64),
    ADD COLUMN effective_config_hash VARCHAR(64),
    ADD COLUMN hash_algorithm_version VARCHAR(64),
    ADD CONSTRAINT fk_agent_task_configuration FOREIGN KEY (config_version_id, agent_id, user_id, config_hash)
        REFERENCES agent_config_version (id, agent_id, user_id, config_hash),
    ADD CONSTRAINT ck_agent_task_configuration CHECK (
        (config_version_id IS NULL AND config_hash IS NULL AND effective_config_hash IS NULL
            AND hash_algorithm_version IS NULL)
        OR (config_version_id IS NOT NULL AND config_hash IS NOT NULL AND effective_config_hash IS NOT NULL
            AND hash_algorithm_version IS NOT NULL AND config_hash ~ '^[0-9a-f]{64}$'
            AND effective_config_hash ~ '^[0-9a-f]{64}$' AND hash_algorithm_version = 'config-canonical-json-v1')
    );
CREATE INDEX idx_agent_task_config_version ON agent_task (config_version_id) WHERE config_version_id IS NOT NULL;
