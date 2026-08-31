-- V25: current-owner cleanup and safe requeue of an already completed document.
-- V10 is immutable; reprocessing deliberately uses an independent durable task.

ALTER TABLE knowledge_document
    DROP CONSTRAINT ck_document_parse_status;

ALTER TABLE knowledge_document
    ADD CONSTRAINT ck_document_parse_status
        CHECK (parse_status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'REPROCESSING'));

ALTER TABLE knowledge_document
    ADD COLUMN vector_generation BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_document_vector_generation_nonnegative
        CHECK (vector_generation >= 0);

ALTER TABLE knowledge_chunk
    ADD COLUMN vector_generation BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_chunk_vector_generation_nonnegative
        CHECK (vector_generation >= 0);

CREATE TABLE knowledge_document_reprocess_task (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    knowledge_base_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    source_vector_generation BIGINT NOT NULL,

    vectors_deleted_at TIMESTAMPTZ,
    chunks_deleted_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,

    cleanup_status VARCHAR(32) NOT NULL,
    failure_summary VARCHAR(500),
    retry_count INTEGER NOT NULL DEFAULT 0,
    last_attempted_at TIMESTAMPTZ NOT NULL,
    last_failed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_document_reprocess_task_document_scope
        FOREIGN KEY (document_id, knowledge_base_id, user_id)
        REFERENCES knowledge_document (id, knowledge_base_id, user_id),
    CONSTRAINT ck_document_reprocess_task_retry_count_nonnegative
        CHECK (retry_count >= 0),
    CONSTRAINT ck_document_reprocess_task_source_generation_range
        CHECK (
            source_vector_generation >= 0
            AND source_vector_generation < 9223372036854775807
        ),
    CONSTRAINT ck_document_reprocess_task_cleanup_status
        CHECK (cleanup_status IN (
            'VECTOR_DELETING',
            'VECTOR_DELETE_RETRYABLE',
            'READY_TO_FINALIZE',
            'COMPLETED'
        )),
    CONSTRAINT ck_document_reprocess_task_lifecycle
        CHECK (
            (
                cleanup_status IN ('VECTOR_DELETING', 'VECTOR_DELETE_RETRYABLE')
                AND vectors_deleted_at IS NULL
                AND chunks_deleted_at IS NULL
                AND completed_at IS NULL
            )
            OR (
                cleanup_status = 'READY_TO_FINALIZE'
                AND vectors_deleted_at IS NOT NULL
                AND chunks_deleted_at IS NULL
                AND completed_at IS NULL
            )
            OR (
                cleanup_status = 'COMPLETED'
                AND vectors_deleted_at IS NOT NULL
                AND chunks_deleted_at IS NOT NULL
                AND completed_at IS NOT NULL
            )
        ),
    CONSTRAINT ck_document_reprocess_task_retryable_failure
        CHECK (
            cleanup_status <> 'VECTOR_DELETE_RETRYABLE'
            OR (
                failure_summary IS NOT NULL
                AND char_length(btrim(failure_summary)) > 0
                AND last_failed_at IS NOT NULL
            )
        )
);

CREATE UNIQUE INDEX uk_document_reprocess_task_active_document
    ON knowledge_document_reprocess_task (document_id)
    WHERE completed_at IS NULL;
