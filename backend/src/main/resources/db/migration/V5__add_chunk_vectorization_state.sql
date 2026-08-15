-- Chunk vectorization is a separate, recoverable stage after V4 text parsing.
-- PostgreSQL remains authoritative for chunk text; a vector store receives only a
-- derived embedding plus filterable metadata. Keep this migration immutable once
-- Flyway has applied it.

-- Existing V4 rows must also be vectorizable. pgcrypto is a PostgreSQL-contributed
-- extension and gives the database the same SHA-256/UTF-8 content hash contract used
-- by the Java ingestion path below.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

ALTER TABLE knowledge_chunk
    ADD COLUMN vectorization_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN vectorization_error VARCHAR(500),
    ADD COLUMN content_hash VARCHAR(64),
    ADD COLUMN vector_id VARCHAR(36);

UPDATE knowledge_chunk
SET content_hash = encode(digest(convert_to(content, 'UTF8'), 'sha256'), 'hex')
WHERE content_hash IS NULL;

ALTER TABLE knowledge_chunk
    ALTER COLUMN content_hash SET NOT NULL,
    ADD CONSTRAINT ck_chunk_vectorization_status
        CHECK (vectorization_status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    ADD CONSTRAINT ck_chunk_content_hash_sha256
        CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT ck_chunk_vectorization_state_fields
        CHECK (
            (vectorization_status = 'COMPLETED'
                AND vector_id IS NOT NULL
                AND vectorization_error IS NULL)
            OR (vectorization_status = 'FAILED'
                AND vector_id IS NULL
                AND char_length(btrim(vectorization_error)) > 0)
            OR (vectorization_status IN ('PENDING', 'PROCESSING')
                AND vector_id IS NULL
                AND vectorization_error IS NULL)
        );

-- The explicit V5 endpoint scans only rows that have not entered vectorization.
-- Document completion/deletion remains checked by the mapper join before any external
-- gateway is invoked.
CREATE INDEX idx_chunk_vectorization_pending_by_kb_user
    ON knowledge_chunk (knowledge_base_id, user_id, document_id, chunk_index ASC, id ASC)
    WHERE vectorization_status = 'PENDING';
