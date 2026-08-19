package com.agentflow.knowledge.vector;

import java.util.List;

/**
 * 中文：业务层唯一的向量库边界。V5 定义幂等 upsert，V7 在同一 provider-neutral 边界增加
 * owner/knowledge-base 范围内的 dense-vector 检索；删除、rerank、sparse 和 hybrid 仍留给
 * 后续独立切片，因此这里没有暴露任何 Qdrant SDK 类型。
 *
 * <p>English: The only vector-store boundary used by business logic. V5 defines
 * idempotent upsert and V7 adds owner/knowledge-base-scoped dense-vector retrieval.
 * Deletion, reranking, sparse, and hybrid retrieval remain deferred, and no Qdrant SDK
 * type appears here.
 */
public interface VectorStoreGateway {

    void upsert(VectorStoreRecord record);

    List<VectorSearchHit> search(VectorSearchRequest request);
}
