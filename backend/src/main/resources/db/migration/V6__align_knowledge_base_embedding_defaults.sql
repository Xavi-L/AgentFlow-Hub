-- V6 configures one real collection for DashScope text-embedding-v4 at 1024 dimensions.
-- Existing knowledge bases are intentionally untouched: changing a model requires a
-- deliberate new-collection and re-vectorization migration, not an implicit data rewrite.
ALTER TABLE knowledge_base
    ALTER COLUMN embedding_provider SET DEFAULT 'dashscope',
    ALTER COLUMN embedding_model SET DEFAULT 'text-embedding-v4';
