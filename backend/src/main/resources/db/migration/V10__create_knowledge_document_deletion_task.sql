-- V24 keeps document metadata as a soft-deleted audit row, but deletion of the
-- derived vectors, controlled source object, and physical chunks spans systems
-- without a distributed transaction. This durable task records the server-owned
-- scope and each completed side effect so a later owner retry can continue safely.
-- Keep this migration immutable once Flyway has applied it.

CREATE TABLE knowledge_document_deletion_task (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    knowledge_base_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,

    -- Snapshot the controlled locator before the document becomes invisible. A retry
    -- never accepts a client-supplied bucket or object key.
    storage_bucket VARCHAR(128) NOT NULL,
    storage_object_key VARCHAR(512) NOT NULL,

    -- Non-null timestamps are durable completion markers for the three ordered steps.
    vectors_deleted_at TIMESTAMPTZ,
    source_deleted_at TIMESTAMPTZ,
    chunks_deleted_at TIMESTAMPTZ,

    -- Failure details remain server-controlled and intentionally concise; callers see
    -- only the stable V24 503 contract.
    failure_summary VARCHAR(500),
    retry_count INTEGER NOT NULL DEFAULT 0,
    last_attempted_at TIMESTAMPTZ,
    last_failed_at TIMESTAMPTZ,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,

    CONSTRAINT fk_document_deletion_task_document_scope
        FOREIGN KEY (document_id, knowledge_base_id, user_id)
        REFERENCES knowledge_document (id, knowledge_base_id, user_id),
    CONSTRAINT uk_document_deletion_task_document
        UNIQUE (document_id),
    CONSTRAINT ck_document_deletion_task_retry_count_nonnegative
        CHECK (retry_count >= 0),
    CONSTRAINT ck_document_deletion_task_completed_steps
        CHECK (
            completed_at IS NULL
            OR (
                vectors_deleted_at IS NOT NULL
                AND source_deleted_at IS NOT NULL
                AND chunks_deleted_at IS NOT NULL
            )
        )
);

-- Owner-scoped retries look up only unfinished work. The unique document key keeps
-- the common single-document path cheap; this partial index also documents the
-- recoverable-task access pattern explicitly.
CREATE INDEX idx_document_deletion_task_pending_scope
    ON knowledge_document_deletion_task (user_id, knowledge_base_id, document_id)
    WHERE completed_at IS NULL;
