package com.agentflow.knowledge.dto;

import java.util.List;
import java.util.Objects;

/**
 * 中文：V8 的无生成结果。它只包含可直接送入未来 ChatGateway 的 context 和与其一一对应的来源，
 * 不包含 prompt、模型调用或回答。
 *
 * <p>English: V8's non-generative result. It contains only context suitable for a future
 * ChatGateway and its one-to-one sources; it contains no prompt, model call, or answer.
 */
public record KnowledgeContextResponse(
        String query,
        int topK,
        int maxContextTokens,
        int usedContextTokens,
        int skippedChunkCount,
        String context,
        List<KnowledgeContextSourceResponse> sources
) {
    public KnowledgeContextResponse {
        query = Objects.requireNonNull(query, "query must not be null");
        if (topK < 1) {
            throw new IllegalArgumentException("topK must be positive");
        }
        if (maxContextTokens < 1) {
            throw new IllegalArgumentException("maxContextTokens must be positive");
        }
        if (usedContextTokens < 0 || usedContextTokens > maxContextTokens) {
            throw new IllegalArgumentException("usedContextTokens must be within the configured budget");
        }
        if (skippedChunkCount < 0) {
            throw new IllegalArgumentException("skippedChunkCount must not be negative");
        }
        context = Objects.requireNonNull(context, "context must not be null");
        sources = List.copyOf(Objects.requireNonNull(sources, "sources must not be null"));
    }
}
