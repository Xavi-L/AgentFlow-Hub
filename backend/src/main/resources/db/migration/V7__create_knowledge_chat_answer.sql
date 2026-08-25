-- V10 persists a successfully validated V9 answer as an append-only audit record.
-- The stored source and citation arrays are JSON text snapshots so later reads never
-- need to re-run V7/V8, Qdrant, or the model. Keep this migration immutable once
-- Flyway has applied it.

CREATE TABLE knowledge_chat_answer (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    knowledge_base_id BIGINT NOT NULL,

    query TEXT NOT NULL,
    answer TEXT NOT NULL,
    top_k INT NOT NULL,
    max_context_tokens INT NOT NULL,
    used_context_tokens INT NOT NULL,
    skipped_chunk_count INT NOT NULL,
    max_answer_tokens INT NOT NULL,

    -- These are canonical JSON-array serializations made immediately after V9 has
    -- validated the answer. TEXT avoids a database-specific JSON type handler in the
    -- application while the checks still reject malformed, empty, or non-array snapshots.
    sources_snapshot_json TEXT NOT NULL,
    citation_ids_json TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_chat_answer_knowledge_base_owner
        FOREIGN KEY (knowledge_base_id, user_id)
        REFERENCES knowledge_base (id, user_id),
    CONSTRAINT ck_chat_answer_query_not_blank
        CHECK (char_length(btrim(query)) > 0),
    CONSTRAINT ck_chat_answer_not_blank
        CHECK (char_length(btrim(answer)) > 0),
    CONSTRAINT ck_chat_answer_top_k
        CHECK (top_k BETWEEN 1 AND 10),
    CONSTRAINT ck_chat_answer_context_budget
        CHECK (max_context_tokens BETWEEN 1 AND 8000),
    CONSTRAINT ck_chat_answer_used_context_budget
        CHECK (used_context_tokens BETWEEN 0 AND max_context_tokens),
    CONSTRAINT ck_chat_answer_skipped_chunk_count
        CHECK (skipped_chunk_count >= 0),
    CONSTRAINT ck_chat_answer_max_answer_budget
        CHECK (max_answer_tokens BETWEEN 1 AND 4096),
    CONSTRAINT ck_chat_answer_sources_snapshot_array
        CHECK (
            CASE WHEN jsonb_typeof(sources_snapshot_json::jsonb) = 'array'
                THEN jsonb_array_length(sources_snapshot_json::jsonb) > 0
                ELSE FALSE
            END
        ),
    CONSTRAINT ck_chat_answer_citation_ids_array
        CHECK (
            CASE WHEN jsonb_typeof(citation_ids_json::jsonb) = 'array'
                THEN jsonb_array_length(citation_ids_json::jsonb) > 0
                ELSE FALSE
            END
        )
);

-- Application code deliberately exposes no update/delete route or service method.
-- The trigger also protects the audit property against accidental direct DML by a
-- normal application connection; retention or migration work must be an explicit
-- later schema decision instead of silently mutating an answer record.
CREATE FUNCTION prevent_knowledge_chat_answer_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'knowledge_chat_answer records are immutable';
END;
$$;

CREATE TRIGGER trg_knowledge_chat_answer_immutable
    BEFORE UPDATE OR DELETE ON knowledge_chat_answer
    FOR EACH ROW
    EXECUTE FUNCTION prevent_knowledge_chat_answer_mutation();
