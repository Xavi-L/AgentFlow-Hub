package com.agentflow.knowledge.model;

/**
 * 中文：一个已落库文本块进入向量存储前后的独立状态机。它不改变 V3/V4 的 document parse
 * 状态，因此可以安全地观察、失败隔离，并在后续单独定义重试策略。
 *
 * <p>English: Independent state machine for a persisted chunk's vectorization stage.
 * It does not alter the V3/V4 document parse state, so observation, failure isolation,
 * and a later retry policy can evolve independently.
 */
public enum ChunkVectorizationStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED
}
