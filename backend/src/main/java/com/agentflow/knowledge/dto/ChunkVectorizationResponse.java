package com.agentflow.knowledge.dto;

/**
 * 中文：一次显式同步向量化请求的汇总。`skipped` 包含已完成、已失败或正在被另一请求处理的 chunk，
 * 因此重复调用不会重复调用 embedding 或覆盖状态。
 *
 * <p>English: Summary of one explicit synchronous vectorization request. `skipped`
 * includes already-completed, failed, or concurrently-claimed chunks, so repeat calls
 * do not repeat embeddings or overwrite state.
 */
public record ChunkVectorizationResponse(
        int discovered,
        int claimed,
        int completed,
        int failed,
        int skipped
) {
}
