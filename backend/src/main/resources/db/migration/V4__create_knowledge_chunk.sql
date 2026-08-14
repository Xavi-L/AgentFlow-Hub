-- Text parsing and chunk persistence. This migration intentionally stops before
-- embeddings and vector storage: PostgreSQL is the source of truth for the
-- deterministic text chunks created from an accepted source document.
--
-- Keep this file immutable once Flyway has applied it. Future vector, retry, or
-- richer metadata work must use V5+ instead of editing this migration.

-- knowledge_chunk carries denormalized owner and knowledge-base columns so later
-- retrieval can filter without a join. This unique key makes the composite foreign
-- key below prove those copied values still match the source document.
ALTER TABLE knowledge_document
    ADD CONSTRAINT uk_knowledge_document_id_kb_user
    UNIQUE (id, knowledge_base_id, user_id);

CREATE TABLE knowledge_chunk (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    knowledge_base_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,

    -- Stable, zero-based position within one document. Reprocessing is deliberately
    -- outside V4; a completed document therefore has one immutable ordered chunk set.
    chunk_index INT NOT NULL,
    content TEXT NOT NULL,
    title_path VARCHAR(512),
    char_count INT NOT NULL,
    token_count INT NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_chunk_document_scope
        FOREIGN KEY (document_id, knowledge_base_id, user_id)
        REFERENCES knowledge_document (id, knowledge_base_id, user_id),
    CONSTRAINT uk_chunk_document_index
        UNIQUE (document_id, chunk_index),
    CONSTRAINT ck_chunk_index_nonnegative
        CHECK (chunk_index >= 0),
    CONSTRAINT ck_chunk_content_not_blank
        CHECK (char_length(btrim(content)) > 0),
    CONSTRAINT ck_chunk_char_count_positive
        CHECK (char_count > 0),
    CONSTRAINT ck_chunk_token_count_positive
        CHECK (token_count > 0)
);

-- A synchronous V4 trigger looks up only unprocessed documents. A later queue can
-- reuse this access path without changing the data contract.
CREATE INDEX idx_document_pending_by_kb_user
    ON knowledge_document (knowledge_base_id, user_id, created_at ASC, id ASC)
    WHERE deleted_at IS NULL AND parse_status = 'PENDING';

-- This is the owner-scoped, document-order access pattern used by V4 verification
-- and is also useful for later retrieval joins.
CREATE INDEX idx_chunk_kb_document_index
    ON knowledge_chunk (knowledge_base_id, document_id, chunk_index ASC);
