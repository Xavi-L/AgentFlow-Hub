package com.agentflow.knowledge.vector;

/**
 * 中文：向量库写入边界。V5 只需要幂等 upsert；搜索、删除和 rerank 留给后续独立切片定义，
 * 因此这里没有暴露任何 Qdrant SDK 类型。
 *
 * <p>English: Vector-store write boundary. V5 needs only idempotent upsert; search,
 * deletion, and reranking are deliberately deferred, and no Qdrant SDK type appears
 * here.
 */
public interface VectorStoreGateway {

    void upsert(VectorStoreRecord record);
}
