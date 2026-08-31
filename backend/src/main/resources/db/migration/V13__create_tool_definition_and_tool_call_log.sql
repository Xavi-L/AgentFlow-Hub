-- V27: the first database-defined built-in tool runtime and its durable call log.
-- V12 is immutable. Agent task/step tables do not exist yet, so the nullable
-- task_id and step_id columns deliberately have no foreign keys in this slice.

CREATE TABLE tool_definition (
    id BIGINT PRIMARY KEY,
    tool_code VARCHAR(128) NOT NULL,
    name VARCHAR(128) NOT NULL,
    description TEXT NOT NULL,
    type VARCHAR(32) NOT NULL,
    input_schema JSONB NOT NULL,
    output_schema JSONB NOT NULL,
    config JSONB NOT NULL,
    timeout_ms INTEGER NOT NULL,
    retry_count INTEGER NOT NULL DEFAULT 0,
    requires_confirmation BOOLEAN NOT NULL DEFAULT FALSE,
    permission_level VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ,

    CONSTRAINT uk_tool_definition_tool_code UNIQUE (tool_code),
    CONSTRAINT ck_tool_definition_tool_code_not_blank
        CHECK (char_length(btrim(tool_code)) > 0),
    CONSTRAINT ck_tool_definition_name_not_blank
        CHECK (char_length(btrim(name)) > 0),
    CONSTRAINT ck_tool_definition_description_not_blank
        CHECK (char_length(btrim(description)) > 0),
    CONSTRAINT ck_tool_definition_type
        CHECK (type IN ('BUILTIN', 'HTTP', 'MCP')),
    CONSTRAINT ck_tool_definition_input_schema_object
        CHECK (jsonb_typeof(input_schema) = 'object'),
    CONSTRAINT ck_tool_definition_output_schema_object
        CHECK (jsonb_typeof(output_schema) = 'object'),
    CONSTRAINT ck_tool_definition_config_object
        CHECK (jsonb_typeof(config) = 'object'),
    CONSTRAINT ck_tool_definition_timeout_positive
        CHECK (timeout_ms > 0),
    CONSTRAINT ck_tool_definition_retry_count_nonnegative
        CHECK (retry_count >= 0),
    CONSTRAINT ck_tool_definition_permission_level
        CHECK (permission_level IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT ck_tool_definition_status
        CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE TABLE tool_call_log (
    id BIGINT PRIMARY KEY,
    task_id BIGINT,
    step_id BIGINT,
    tool_id BIGINT NOT NULL,
    tool_code VARCHAR(128) NOT NULL,
    tool_name VARCHAR(128) NOT NULL,
    arguments JSONB NOT NULL,
    result JSONB,
    status VARCHAR(32) NOT NULL,
    retry_count INTEGER NOT NULL DEFAULT 0,
    latency_ms INTEGER,
    error_code VARCHAR(64),
    error_message VARCHAR(500),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_tool_call_log_tool
        FOREIGN KEY (tool_id) REFERENCES tool_definition (id),
    CONSTRAINT ck_tool_call_log_tool_code_not_blank
        CHECK (char_length(btrim(tool_code)) > 0),
    CONSTRAINT ck_tool_call_log_tool_name_not_blank
        CHECK (char_length(btrim(tool_name)) > 0),
    CONSTRAINT ck_tool_call_log_status
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED', 'TIMEOUT', 'REJECTED')),
    CONSTRAINT ck_tool_call_log_retry_count_nonnegative
        CHECK (retry_count >= 0),
    CONSTRAINT ck_tool_call_log_latency_nonnegative
        CHECK (latency_ms IS NULL OR latency_ms >= 0),
    CONSTRAINT ck_tool_call_log_terminal_fields
        CHECK (
            (
                status = 'PENDING'
                AND started_at IS NULL
                AND finished_at IS NULL
                AND latency_ms IS NULL
                AND result IS NULL
                AND error_code IS NULL
                AND error_message IS NULL
            )
            OR (
                status = 'RUNNING'
                AND started_at IS NOT NULL
                AND finished_at IS NULL
                AND latency_ms IS NULL
                AND result IS NULL
                AND error_code IS NULL
                AND error_message IS NULL
            )
            OR (
                status = 'SUCCESS'
                AND started_at IS NOT NULL
                AND finished_at IS NOT NULL
                AND latency_ms IS NOT NULL
                AND result IS NOT NULL
                AND jsonb_typeof(result) = 'object'
                AND error_code IS NULL
                AND error_message IS NULL
            )
            OR (
                status IN ('FAILED', 'TIMEOUT', 'REJECTED')
                AND started_at IS NOT NULL
                AND finished_at IS NOT NULL
                AND latency_ms IS NOT NULL
                AND result IS NOT NULL
                AND jsonb_typeof(result) = 'object'
                AND error_code IS NOT NULL
                AND char_length(btrim(error_code)) > 0
                AND error_message IS NOT NULL
                AND char_length(btrim(error_message)) > 0
            )
        )
);

CREATE INDEX idx_tool_call_task
    ON tool_call_log (task_id);

CREATE INDEX idx_tool_call_step
    ON tool_call_log (step_id);

CREATE INDEX idx_tool_call_tool
    ON tool_call_log (tool_id, created_at DESC);

INSERT INTO tool_definition (
    id,
    tool_code,
    name,
    description,
    type,
    input_schema,
    output_schema,
    config,
    timeout_ms,
    retry_count,
    requires_confirmation,
    permission_level,
    status,
    created_at,
    updated_at
) VALUES (
    270000000000000001,
    'order_query',
    'Order Query',
    'Query one shared demo order by order number without modifying business data.',
    'BUILTIN',
    '{
      "type": "object",
      "properties": {
        "orderNo": {
          "type": "string",
          "description": "Demo order number, for example order_1024",
          "minLength": 1,
          "maxLength": 64
        }
      },
      "required": ["orderNo"],
      "additionalProperties": false
    }'::jsonb,
    '{
      "type": "object",
      "properties": {
        "orderNo": {"type": "string"},
        "amount": {"type": "number"},
        "currency": {"type": "string"},
        "status": {"type": "string"},
        "paymentStatus": {"type": "string"},
        "errorCode": {"type": ["string", "null"]}
      },
      "required": ["orderNo", "amount", "currency", "status", "paymentStatus", "errorCode"],
      "additionalProperties": false
    }'::jsonb,
    '{"handler": "orderQueryTool", "readonly": true}'::jsonb,
    3000,
    0,
    FALSE,
    'MEDIUM',
    'ACTIVE',
    TIMESTAMPTZ '2026-05-01 12:00:00+08:00',
    TIMESTAMPTZ '2026-05-01 12:00:00+08:00'
);
