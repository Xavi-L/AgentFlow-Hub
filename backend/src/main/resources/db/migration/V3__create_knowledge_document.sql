-- The first document-ingestion slice. It stores original-document metadata and a
-- server-generated storage key only; parsing, chunks, embeddings, and retrieval are
-- intentionally separate later migrations.
--
-- Keep this file immutable once Flyway has applied it. Any later schema change must
-- use V4+ rather than editing this migration.

-- A document keeps user_id for owner-scoped queries. The composite uniqueness lets the
-- foreign key below prove that its user_id matches the owning knowledge base.
ALTER TABLE knowledge_base
    ADD CONSTRAINT uk_knowledge_base_id_user UNIQUE (id, user_id);

CREATE TABLE knowledge_document (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    knowledge_base_id BIGINT NOT NULL,

    file_name VARCHAR(255) NOT NULL,
    file_type VARCHAR(32) NOT NULL,
    mime_type VARCHAR(128) NOT NULL,
    file_size BIGINT NOT NULL,

    -- Never store an absolute host path or a user-controlled filename here. The
    -- application generates an opaque, relative object key for the configured store.
    storage_bucket VARCHAR(128) NOT NULL,
    storage_object_key VARCHAR(512) NOT NULL,

    -- PENDING means the file is durably accepted but has not entered the later parsing
    -- and chunking pipeline yet.
    parse_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    parse_error TEXT,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ,

    CONSTRAINT fk_document_knowledge_base_owner
        FOREIGN KEY (knowledge_base_id, user_id)
        REFERENCES knowledge_base (id, user_id),
    CONSTRAINT ck_document_file_name_not_blank
        CHECK (char_length(btrim(file_name)) > 0),
    CONSTRAINT ck_document_file_type
        CHECK (file_type IN ('TXT', 'MD')),
    CONSTRAINT ck_document_file_size_positive
        CHECK (file_size > 0),
    CONSTRAINT ck_document_parse_status
        CHECK (parse_status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    CONSTRAINT uk_document_storage_object
        UNIQUE (storage_bucket, storage_object_key)
);

-- Lists are always scoped by both the parent knowledge base and its owner, omit soft
-- deletions, and present the newest document first.
CREATE INDEX idx_document_kb_user_created
    ON knowledge_document (knowledge_base_id, user_id, created_at DESC, id DESC)
    WHERE deleted_at IS NULL;
