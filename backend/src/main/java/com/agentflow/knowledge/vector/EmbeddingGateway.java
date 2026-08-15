package com.agentflow.knowledge.vector;

/**
 * 中文：向量化业务层唯一依赖的 embedding 边界。具体的 OpenAI-compatible、Spring AI 或本地
 * 模型客户端只能实现本接口，不能泄漏到 chunk 编排服务。
 *
 * <p>English: The only embedding boundary used by vectorization business logic.
 * OpenAI-compatible, Spring AI, or local-model clients must implement this interface
 * instead of leaking into chunk orchestration.
 */
public interface EmbeddingGateway {

    EmbeddingVector embed(EmbeddingRequest request);
}
