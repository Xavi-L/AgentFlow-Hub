-- Knowledge-base metadata. This migration deliberately creates only the root resource;
-- documents, chunks, embedding, and vector storage belong to later migrations/slices.
--
-- Keep this file immutable once it has been applied. Any later schema change must use V3+.

CREATE TABLE knowledge_base (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    description TEXT,
    embedding_provider VARCHAR(64) NOT NULL DEFAULT 'openai-compatible',
    embedding_model VARCHAR(128) NOT NULL DEFAULT 'text-embedding-v3',
    chunk_size INT NOT NULL DEFAULT 800,
    chunk_overlap INT NOT NULL DEFAULT 120,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ,

    CONSTRAINT fk_knowledge_base_user
        FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT ck_knowledge_base_name_not_blank
        CHECK (char_length(btrim(name)) > 0),
    CONSTRAINT ck_knowledge_base_status
        CHECK (status IN ('ACTIVE', 'DISABLED')),
    -- These bounds come from the RAG design's current safe chunking range.
    CONSTRAINT ck_knowledge_base_chunk_size
        CHECK (chunk_size BETWEEN 80 AND 1000),
    CONSTRAINT ck_knowledge_base_chunk_overlap
        CHECK (chunk_overlap >= 0 AND chunk_overlap < chunk_size)
);

-- The list endpoint always scopes by owner, hides soft-deleted rows, and sorts newest first.
CREATE INDEX idx_kb_user_created
    ON knowledge_base (user_id, created_at DESC, id DESC)
    WHERE deleted_at IS NULL;

-- Future document upload/retrieval paths will need a fast owner-and-status lookup.
CREATE INDEX idx_kb_user_status
    ON knowledge_base (user_id, status)
    WHERE deleted_at IS NULL;
