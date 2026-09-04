-- M4D establishes task-scoped execution Trace before AgentEngine is connected to TaskRunner.
-- Trace rows are retained by default: all foreign keys deliberately use PostgreSQL NO ACTION,
-- and RAG hit source identifiers are historical snapshots rather than live source foreign keys.

CREATE TABLE agent_step (
    id BIGINT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    step_index INT NOT NULL,
    step_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    title VARCHAR(255) NOT NULL,
    summary JSONB NOT NULL DEFAULT '{}'::jsonb,
    error_code VARCHAR(64),
    error_message VARCHAR(500),
    started_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ,
    latency_ms BIGINT,
    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_agent_step_task
        FOREIGN KEY (task_id) REFERENCES agent_task (id),
    CONSTRAINT uk_agent_step_task_index
        UNIQUE (task_id, step_index),
    CONSTRAINT uk_agent_step_id_task
        UNIQUE (id, task_id),
    CONSTRAINT uk_agent_step_id_task_type
        UNIQUE (id, task_id, step_type),
    CONSTRAINT ck_agent_step_index_nonnegative
        CHECK (step_index >= 0),
    CONSTRAINT ck_agent_step_type
        CHECK (step_type IN (
            'PRE_RETRIEVAL', 'LLM_DECISION', 'TOOL_CALL', 'LLM_FINAL_GENERATION'
        )),
    CONSTRAINT ck_agent_step_status
        CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED', 'SKIPPED')),
    CONSTRAINT ck_agent_step_title_not_blank
        CHECK (char_length(btrim(title)) > 0),
    CONSTRAINT ck_agent_step_summary_object
        CHECK (jsonb_typeof(summary) = 'object'),
    CONSTRAINT ck_agent_step_latency_nonnegative
        CHECK (latency_ms IS NULL OR latency_ms >= 0),
    CONSTRAINT ck_agent_step_lifecycle
        CHECK (
            (
                status = 'RUNNING'
                AND ended_at IS NULL
                AND latency_ms IS NULL
                AND error_code IS NULL
                AND error_message IS NULL
            )
            OR (
                status IN ('SUCCESS', 'SKIPPED')
                AND ended_at IS NOT NULL
                AND latency_ms IS NOT NULL
                AND error_code IS NULL
                AND error_message IS NULL
            )
            OR (
                status = 'FAILED'
                AND ended_at IS NOT NULL
                AND latency_ms IS NOT NULL
                AND error_code IS NOT NULL
                AND char_length(btrim(error_code)) > 0
                AND error_message IS NOT NULL
                AND char_length(btrim(error_message)) > 0
            )
        ),
    CONSTRAINT ck_agent_step_times
        CHECK (
            started_at >= created_at
            AND (ended_at IS NULL OR ended_at >= started_at)
        )
);

CREATE TABLE llm_call_log (
    id BIGINT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    step_id BIGINT NOT NULL,
    call_type VARCHAR(32) NOT NULL,
    expected_step_type VARCHAR(32) GENERATED ALWAYS AS (
        CASE call_type
            WHEN 'DECISION' THEN 'LLM_DECISION'
            WHEN 'FINAL_GENERATION' THEN 'LLM_FINAL_GENERATION'
        END
    ) STORED,
    provider VARCHAR(64) NOT NULL,
    requested_model VARCHAR(128) NOT NULL,
    resolved_model VARCHAR(128),
    request_snapshot JSONB NOT NULL,
    response_text TEXT,
    finish_reason VARCHAR(64),
    provider_request_id VARCHAR(255),
    input_tokens INT,
    output_tokens INT,
    total_tokens INT,
    usage_quality VARCHAR(32) NOT NULL,
    latency_ms BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    error_code VARCHAR(64),
    error_message VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_llm_call_log_step_task
        FOREIGN KEY (step_id, task_id) REFERENCES agent_step (id, task_id),
    CONSTRAINT fk_llm_call_log_step_task_type
        FOREIGN KEY (step_id, task_id, expected_step_type)
        REFERENCES agent_step (id, task_id, step_type),
    CONSTRAINT ck_llm_call_log_call_type
        CHECK (call_type IN ('DECISION', 'FINAL_GENERATION')),
    CONSTRAINT ck_llm_call_log_provider_not_blank
        CHECK (char_length(btrim(provider)) > 0),
    CONSTRAINT ck_llm_call_log_requested_model_not_blank
        CHECK (char_length(btrim(requested_model)) > 0),
    CONSTRAINT ck_llm_call_log_resolved_model_not_blank
        CHECK (resolved_model IS NULL OR char_length(btrim(resolved_model)) > 0),
    CONSTRAINT ck_llm_call_log_request_snapshot_object
        CHECK (jsonb_typeof(request_snapshot) = 'object'),
    CONSTRAINT ck_llm_call_log_usage_quality
        CHECK (usage_quality IN ('EXACT', 'ESTIMATED', 'MIXED', 'UNKNOWN')),
    CONSTRAINT ck_llm_call_log_token_usage
        CHECK (
            (
                usage_quality = 'UNKNOWN'
                AND input_tokens IS NULL
                AND output_tokens IS NULL
                AND total_tokens IS NULL
            )
            OR (
                usage_quality IN ('EXACT', 'ESTIMATED', 'MIXED')
                AND input_tokens IS NOT NULL
                AND input_tokens >= 0
                AND output_tokens IS NOT NULL
                AND output_tokens >= 0
                AND total_tokens IS NOT NULL
                AND total_tokens >= 0
                AND total_tokens = input_tokens + output_tokens
            )
        ),
    CONSTRAINT ck_llm_call_log_latency_nonnegative
        CHECK (latency_ms >= 0),
    CONSTRAINT ck_llm_call_log_status
        CHECK (status IN ('SUCCESS', 'FAILED')),
    CONSTRAINT ck_llm_call_log_status_error_shape
        CHECK (
            (
                status = 'SUCCESS'
                AND error_code IS NULL
                AND error_message IS NULL
            )
            OR (
                status = 'FAILED'
                AND error_code IS NOT NULL
                AND char_length(btrim(error_code)) > 0
                AND error_message IS NOT NULL
                AND char_length(btrim(error_message)) > 0
            )
        )
);

CREATE INDEX idx_llm_call_log_task_created_id
    ON llm_call_log (task_id, created_at, id);

CREATE INDEX idx_llm_call_log_step_id
    ON llm_call_log (step_id, id);

CREATE TABLE rag_retrieval_log (
    id BIGINT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    step_id BIGINT NOT NULL,
    query TEXT NOT NULL,
    embedding_profile_code VARCHAR(128) NOT NULL,
    corpus_snapshot JSONB NOT NULL,
    top_k INT NOT NULL,
    similarity_threshold NUMERIC(8,6) NOT NULL,
    candidate_count INT NOT NULL,
    valid_hit_count INT NOT NULL,
    stale_hit_count INT NOT NULL,
    latency_ms BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    error_code VARCHAR(64),
    error_message VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_rag_retrieval_log_step_task
        FOREIGN KEY (step_id, task_id) REFERENCES agent_step (id, task_id),
    CONSTRAINT ck_rag_retrieval_log_query_not_blank
        CHECK (char_length(btrim(query)) > 0),
    CONSTRAINT ck_rag_retrieval_log_embedding_profile_not_blank
        CHECK (char_length(btrim(embedding_profile_code)) > 0),
    CONSTRAINT ck_rag_retrieval_log_corpus_snapshot_object
        CHECK (jsonb_typeof(corpus_snapshot) = 'object'),
    CONSTRAINT ck_rag_retrieval_log_top_k_positive
        CHECK (top_k > 0),
    CONSTRAINT ck_rag_retrieval_log_similarity_threshold
        CHECK (similarity_threshold BETWEEN -1 AND 1),
    CONSTRAINT ck_rag_retrieval_log_counts
        CHECK (
            candidate_count >= 0
            AND valid_hit_count >= 0
            AND stale_hit_count >= 0
            AND valid_hit_count <= top_k
            AND valid_hit_count + stale_hit_count <= candidate_count
        ),
    CONSTRAINT ck_rag_retrieval_log_latency_nonnegative
        CHECK (latency_ms >= 0),
    CONSTRAINT ck_rag_retrieval_log_status
        CHECK (status IN ('SUCCESS', 'FAILED')),
    CONSTRAINT ck_rag_retrieval_log_status_error_shape
        CHECK (
            (
                status = 'SUCCESS'
                AND error_code IS NULL
                AND error_message IS NULL
            )
            OR (
                status = 'FAILED'
                AND error_code IS NOT NULL
                AND char_length(btrim(error_code)) > 0
                AND error_message IS NOT NULL
                AND char_length(btrim(error_message)) > 0
            )
        )
);

CREATE INDEX idx_rag_retrieval_log_task_created_id
    ON rag_retrieval_log (task_id, created_at, id);

CREATE INDEX idx_rag_retrieval_log_step_id
    ON rag_retrieval_log (step_id, id);

CREATE TABLE rag_retrieval_hit (
    id BIGINT PRIMARY KEY,
    retrieval_id BIGINT NOT NULL,
    rank_no INT NOT NULL,
    citation_id VARCHAR(32) NOT NULL,
    chunk_id_snapshot BIGINT NOT NULL,
    document_id_snapshot BIGINT NOT NULL,
    knowledge_base_id_snapshot BIGINT NOT NULL,
    vector_generation BIGINT NOT NULL,
    score NUMERIC(10,8) NOT NULL,
    content_snapshot TEXT NOT NULL,
    metadata_snapshot JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_rag_retrieval_hit_retrieval
        FOREIGN KEY (retrieval_id) REFERENCES rag_retrieval_log (id),
    CONSTRAINT uk_rag_retrieval_hit_rank
        UNIQUE (retrieval_id, rank_no),
    CONSTRAINT uk_rag_retrieval_hit_citation
        UNIQUE (retrieval_id, citation_id),
    CONSTRAINT ck_rag_retrieval_hit_rank_positive
        CHECK (rank_no > 0),
    CONSTRAINT ck_rag_retrieval_hit_citation_not_blank
        CHECK (char_length(btrim(citation_id)) > 0),
    CONSTRAINT ck_rag_retrieval_hit_source_ids_positive
        CHECK (
            chunk_id_snapshot > 0
            AND document_id_snapshot > 0
            AND knowledge_base_id_snapshot > 0
        ),
    CONSTRAINT ck_rag_retrieval_hit_vector_generation_nonnegative
        CHECK (vector_generation >= 0),
    CONSTRAINT ck_rag_retrieval_hit_score
        CHECK (score BETWEEN -1 AND 1),
    CONSTRAINT ck_rag_retrieval_hit_content_not_blank
        CHECK (char_length(btrim(content_snapshot)) > 0),
    CONSTRAINT ck_rag_retrieval_hit_metadata_object
        CHECK (jsonb_typeof(metadata_snapshot) = 'object')
);

ALTER TABLE tool_call_log
    ADD CONSTRAINT ck_tool_call_log_task_step_pair
        CHECK (
            (task_id IS NULL AND step_id IS NULL)
            OR (task_id IS NOT NULL AND step_id IS NOT NULL)
        ),
    ADD CONSTRAINT fk_tool_call_log_step_task
        FOREIGN KEY (step_id, task_id) REFERENCES agent_step (id, task_id);

CREATE INDEX idx_tool_call_log_task_created_id
    ON tool_call_log (task_id, created_at, id);
