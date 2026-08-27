package com.agentflow.knowledge.dto;

import com.agentflow.knowledge.model.KnowledgeChatAnswer;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 中文：V11 回答审计台账的一行分页摘要。它刻意不含 answer、V8 预算或 sources：这些冻结的
 * 完整字段只能通过 V10 的单条 answerId 详情读取。
 *
 * <p>English: One paged V11 answer-audit ledger summary. It deliberately excludes answer,
 * V8 budgets, and sources: those frozen full fields remain available only from V10's
 * single-answer detail endpoint.</p>
 */
public record KnowledgeChatAnswerSummaryResponse(
        String answerId,
        String query,
        List<String> citationIds,
        OffsetDateTime createdAt
) {
    public KnowledgeChatAnswerSummaryResponse {
        answerId = requireNonBlank(answerId, "answerId");
        query = requireNonBlank(query, "query");
        citationIds = List.copyOf(Objects.requireNonNull(citationIds, "citationIds must not be null"));
        if (citationIds.isEmpty()) {
            throw new IllegalArgumentException("citationIds must not be empty");
        }
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public static KnowledgeChatAnswerSummaryResponse from(
            KnowledgeChatAnswer answer,
            List<String> citationIds
    ) {
        Objects.requireNonNull(answer, "answer must not be null");
        if (answer.getId() == null) {
            throw new IllegalArgumentException("persisted answer id must not be null");
        }
        return new KnowledgeChatAnswerSummaryResponse(
                answer.getId().toString(),
                answer.getQuery(),
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
