package com.agentflow.knowledge.dto;

import com.agentflow.knowledge.model.KnowledgeChatAnswer;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 中文：V10 的不可变回答读取模型。POST 与 GET 使用同一结构；GET 的 sources/citationIds 仅从
 * 已持久化的快照反序列化，不会触发新的检索或模型调用。
 *
 * <p>English: Immutable V10 answer-read model. POST and GET use the same shape; GET
 * deserializes sources/citationIds only from the persisted snapshot and never triggers a new
 * retrieval or model call.</p>
 */
public record KnowledgeChatAnswerResponse(
        String answerId,
        String answer,
        String query,
        int topK,
        int maxContextTokens,
        int usedContextTokens,
        int skippedChunkCount,
        int maxAnswerTokens,
        List<KnowledgeContextSourceResponse> sources,
        List<String> citationIds,
        OffsetDateTime createdAt
) {
    public KnowledgeChatAnswerResponse {
        answerId = requireNonBlank(answerId, "answerId");
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
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("sources must not be empty");
        }
        if (citationIds.isEmpty()) {
            throw new IllegalArgumentException("citationIds must not be empty");
        }
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public static KnowledgeChatAnswerResponse from(
            KnowledgeChatAnswer answer,
            List<KnowledgeContextSourceResponse> sources,
            List<String> citationIds
    ) {
        Objects.requireNonNull(answer, "answer must not be null");
        if (answer.getId() == null) {
            throw new IllegalArgumentException("persisted answer id must not be null");
        }
        return new KnowledgeChatAnswerResponse(
                answer.getId().toString(),
                answer.getAnswer(),
                answer.getQuery(),
                answer.getTopK(),
                answer.getMaxContextTokens(),
                answer.getUsedContextTokens(),
                answer.getSkippedChunkCount(),
                answer.getMaxAnswerTokens(),
                sources,
                citationIds,
                answer.getCreatedAt()
        );
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
