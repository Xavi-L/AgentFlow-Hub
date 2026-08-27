-- V11 reads the immutable V10 ledger only by authenticated owner and one knowledge base.
-- This composite order supports its fixed created_at DESC, id DESC pagination without
-- creating an index for answer detail, retrieval, generation, or mutation workflows.
CREATE INDEX idx_knowledge_chat_answer_list_owner_kb_created_id
    ON knowledge_chat_answer (user_id, knowledge_base_id, created_at DESC, id DESC);
