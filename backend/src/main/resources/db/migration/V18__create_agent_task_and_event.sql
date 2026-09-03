-- M4C establishes the durable task root and its event cursor before any public task API.
-- Keep this migration immutable after it has been applied. Later Trace, SSE, and Engine
-- integration must use higher-numbered migrations.

CREATE TABLE agent_task (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    agent_id BIGINT NOT NULL,
    client_request_id VARCHAR(128) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    phase VARCHAR(32),
    termination_reason VARCHAR(64),
    user_input TEXT NOT NULL,
    execution_snapshot JSONB NOT NULL,
    max_decision_turns INT NOT NULL,
    max_tool_calls INT NOT NULL,
    max_total_tokens INT NOT NULL,
    reserved_final_tokens INT NOT NULL,
    decision_turns_used INT NOT NULL DEFAULT 0,
    tool_calls_used INT NOT NULL DEFAULT 0,
    input_tokens INT NOT NULL DEFAULT 0,
    output_tokens INT NOT NULL DEFAULT 0,
    total_tokens INT NOT NULL DEFAULT 0,
    token_usage_quality VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
    final_answer TEXT,
    citations JSONB NOT NULL DEFAULT '[]'::jsonb,
    error_code VARCHAR(64),
    error_message VARCHAR(500),
    cancel_requested_at TIMESTAMPTZ,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    last_event_sequence BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version INT NOT NULL DEFAULT 0,

    CONSTRAINT fk_agent_task_owner_agent
        FOREIGN KEY (agent_id, user_id) REFERENCES agent_app (id, user_id),
    CONSTRAINT uk_agent_task_user_client_request
        UNIQUE (user_id, client_request_id),
    CONSTRAINT ck_agent_task_client_request_id
        CHECK (char_length(client_request_id) BETWEEN 1 AND 128
            AND client_request_id !~ '^[[:space:]]*$'),
    CONSTRAINT ck_agent_task_request_fingerprint
        CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_agent_task_status
        CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED', 'TIMED_OUT')),
    CONSTRAINT ck_agent_task_phase
        CHECK (phase IS NULL OR phase IN (
            'PREPARING', 'RETRIEVING', 'DECIDING', 'EXECUTING_TOOL', 'GENERATING'
        )),
    CONSTRAINT ck_agent_task_termination_reason
        CHECK (termination_reason IS NULL OR termination_reason IN (
            'ANSWERED', 'MAX_DECISION_TURNS', 'MAX_TOOL_CALLS',
            'TOKEN_BUDGET_EXHAUSTED', 'DEADLINE_EXCEEDED', 'USER_CANCELLED', 'SYSTEM_ERROR'
        )),
    CONSTRAINT ck_agent_task_token_usage_quality
        CHECK (token_usage_quality IN ('EXACT', 'ESTIMATED', 'MIXED', 'UNKNOWN')),
    CONSTRAINT ck_agent_task_user_input
        CHECK (char_length(user_input) > 0 AND user_input !~ '^[[:space:]]*$'),
    CONSTRAINT ck_agent_task_snapshot_object
        CHECK (jsonb_typeof(execution_snapshot) = 'object'),
    CONSTRAINT ck_agent_task_citations_array
        CHECK (jsonb_typeof(citations) = 'array'),
    CONSTRAINT ck_agent_task_budget_limits
        CHECK (
            max_decision_turns BETWEEN 1 AND 20
            AND max_tool_calls BETWEEN 0 AND 20
            AND max_tool_calls < max_decision_turns
            AND max_total_tokens BETWEEN 256 AND 100000
            AND reserved_final_tokens >= 1
            AND reserved_final_tokens < max_total_tokens
        ),
    CONSTRAINT ck_agent_task_used_counters
        CHECK (
            decision_turns_used BETWEEN 0 AND max_decision_turns
            AND tool_calls_used BETWEEN 0 AND max_tool_calls
        ),
    CONSTRAINT ck_agent_task_token_totals
        CHECK (
            input_tokens >= 0
            AND output_tokens >= 0
            AND total_tokens >= 0
            AND total_tokens = input_tokens + output_tokens
            AND total_tokens <= max_total_tokens
        ),
    CONSTRAINT ck_agent_task_event_cursor_version
        CHECK (last_event_sequence >= 0 AND version >= 0),
    CONSTRAINT ck_agent_task_status_shape
        CHECK (
            (status = 'QUEUED'
                AND phase IS NULL
                AND termination_reason IS NULL
                AND started_at IS NULL
                AND completed_at IS NULL)
            OR
            (status = 'RUNNING'
                AND phase IS NOT NULL
                AND termination_reason IS NULL
                AND started_at IS NOT NULL
                AND completed_at IS NULL)
            OR
            (status IN ('COMPLETED', 'FAILED', 'CANCELLED', 'TIMED_OUT')
                AND phase IS NULL
                AND termination_reason IS NOT NULL
                AND completed_at IS NOT NULL)
        ),
    CONSTRAINT ck_agent_task_terminal_reason_mapping
        CHECK (
            (status = 'COMPLETED' AND termination_reason IN (
                'ANSWERED', 'MAX_DECISION_TURNS', 'MAX_TOOL_CALLS'
            ))
            OR (status = 'FAILED' AND termination_reason IN (
                'TOKEN_BUDGET_EXHAUSTED', 'SYSTEM_ERROR'
            ))
            OR (status = 'CANCELLED' AND termination_reason = 'USER_CANCELLED')
            OR (status = 'TIMED_OUT' AND termination_reason = 'DEADLINE_EXCEEDED')
            OR (status IN ('QUEUED', 'RUNNING') AND termination_reason IS NULL)
        ),
    CONSTRAINT ck_agent_task_result_shape
        CHECK (
            (status IN ('QUEUED', 'RUNNING', 'CANCELLED', 'TIMED_OUT')
                AND final_answer IS NULL
                AND error_code IS NULL
                AND error_message IS NULL)
            OR
            (status = 'COMPLETED'
                AND final_answer IS NOT NULL
                AND final_answer !~ '^[[:space:]]*$'
                AND error_code IS NULL
                AND error_message IS NULL)
            OR
            (status = 'FAILED'
                AND final_answer IS NULL
                AND error_code IS NOT NULL
                AND error_code !~ '^[[:space:]]*$'
                AND error_message IS NOT NULL
                AND error_message !~ '^[[:space:]]*$')
        ),
    CONSTRAINT ck_agent_task_cancel_shape
        CHECK (
            (status = 'CANCELLED' AND cancel_requested_at IS NOT NULL)
            OR (status = 'RUNNING')
            OR (status NOT IN ('RUNNING', 'CANCELLED') AND cancel_requested_at IS NULL)
        ),
    CONSTRAINT ck_agent_task_execution_times
        CHECK (
            updated_at >= created_at
            AND (started_at IS NULL OR started_at >= created_at)
            AND (completed_at IS NULL OR completed_at >= created_at)
            AND (started_at IS NULL OR completed_at IS NULL OR completed_at >= started_at)
            AND (cancel_requested_at IS NULL OR cancel_requested_at >= created_at)
            AND (status NOT IN ('COMPLETED', 'TIMED_OUT') OR started_at IS NOT NULL)
        )
);

CREATE INDEX idx_agent_task_user_created
    ON agent_task (user_id, created_at DESC, id DESC);

CREATE INDEX idx_agent_task_agent_status_created
    ON agent_task (agent_id, status, created_at, id);

CREATE INDEX idx_agent_task_active_created
    ON agent_task (status, created_at, id)
    WHERE status IN ('QUEUED', 'RUNNING');

CREATE INDEX idx_agent_task_cancel_requested
    ON agent_task (cancel_requested_at)
    WHERE status = 'RUNNING' AND cancel_requested_at IS NOT NULL;

CREATE TABLE agent_task_event (
    id BIGINT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    sequence_no BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_agent_task_event_task
        FOREIGN KEY (task_id) REFERENCES agent_task (id),
    CONSTRAINT uk_agent_task_event_sequence
        UNIQUE (task_id, sequence_no),
    CONSTRAINT ck_agent_task_event_sequence
        CHECK (sequence_no > 0),
    CONSTRAINT ck_agent_task_event_type
        CHECK (event_type IN (
            'TASK_CREATED', 'TASK_STARTED', 'PHASE_CHANGED', 'RAG_FINISHED',
            'DECISION_FINISHED', 'TOOL_STARTED', 'TOOL_FINISHED',
            'FINAL_GENERATION_STARTED', 'ANSWER_CHUNK', 'TASK_COMPLETED',
            'TASK_FAILED', 'TASK_CANCELLED', 'TASK_TIMED_OUT'
        )),
    CONSTRAINT ck_agent_task_event_payload_object
        CHECK (jsonb_typeof(payload) = 'object')
);
