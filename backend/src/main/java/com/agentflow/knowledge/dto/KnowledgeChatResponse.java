package com.agentflow.knowledge.dto;

import java.util.List;
import java.util.Objects;

/**
 * 中文：V9 单次回答的可追溯结果。V8 的检索/预算字段与 sources 直接复制，citationIds 则只来自
 * 服务端对 answer 的校验结果。
 *
 * <p>English: Traceable result of one V9 answer. V8 retrieval/budget fields and sources
 * are copied directly; citationIds come only from server-side validation of the answer.
 */
public record KnowledgeChatResponse(
        String answer,
        String query,
        int topK,
        int maxContextTokens,
        int usedContextTokens,
        int skippedChunkCount,
        int maxAnswerTokens,
        List<KnowledgeContextSourceResponse> sources,
        List<String> citationIds
) {
    public KnowledgeChatResponse {
        answer = requireNonBlank(answer, "answer");
        query = requireNonBlank(query, "query");
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
        if (maxAnswerTokens < 1) {
            throw new IllegalArgumentException("maxAnswerTokens must be positive");
        }
        sources = List.copyOf(Objects.requireNonNull(sources, "sources must not be null"));
        citationIds = List.copyOf(Objects.requireNonNull(citationIds, "citationIds must not be null"));
        if (citationIds.isEmpty()) {
            throw new IllegalArgumentException("citationIds must not be empty");
        }
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
