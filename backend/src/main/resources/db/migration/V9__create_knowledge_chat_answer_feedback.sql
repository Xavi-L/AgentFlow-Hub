-- V12 records a single user judgement for one immutable V10 answer. This is intentionally a
-- separate append-only event table: it does not alter answer text, budgets, or source snapshots.
-- Keep this migration immutable once Flyway has applied it.

CREATE TABLE knowledge_chat_answer_feedback (
    id BIGINT PRIMARY KEY,
    answer_id BIGINT NOT NULL,
    verdict VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_chat_answer_feedback_answer
        FOREIGN KEY (answer_id)
        REFERENCES knowledge_chat_answer (id),
    -- One persisted answer can carry exactly one feedback event. Retrying with the same verdict
    -- reads this row; an opposite verdict is a service-level 409 rather than an UPDATE.
    CONSTRAINT uk_chat_answer_feedback_answer
        UNIQUE (answer_id),
    CONSTRAINT ck_chat_answer_feedback_verdict
        CHECK (verdict IN ('HELPFUL', 'NOT_HELPFUL'))
);

-- The API offers no update/delete path. The trigger closes the same gap for accidental direct
-- DML by a normal application connection; retention/deletion work is deliberately a later
-- policy decision instead of hidden feedback mutation.
CREATE FUNCTION prevent_knowledge_chat_answer_feedback_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'knowledge_chat_answer_feedback records are immutable';
END;
$$;

CREATE TRIGGER trg_knowledge_chat_answer_feedback_immutable
    BEFORE UPDATE OR DELETE ON knowledge_chat_answer_feedback
    FOR EACH ROW
    EXECUTE FUNCTION prevent_knowledge_chat_answer_feedback_mutation();
