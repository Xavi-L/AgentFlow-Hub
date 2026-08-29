package com.agentflow.knowledge.dto;

import com.agentflow.knowledge.model.KnowledgeChatAnswerFeedbackCoverageItem;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 中文：V17 不可变回答反馈覆盖索引的一行窄响应。响应严格只有 answerId、submitted 和
 * answerCreatedAt；反馈 verdict、feedbackId、反馈时间及回答内容由其他切片负责。
 *
 * <p>English: Narrow V17 response for one immutable-answer feedback-coverage index row. The
 * response contains exactly answerId, submitted, and answerCreatedAt; verdict, feedbackId,
 * feedback time, and answer content belong to other slices.</p>
 */
public record KnowledgeChatAnswerFeedbackCoverageItemResponse(
        String answerId,
        boolean submitted,
        OffsetDateTime answerCreatedAt
) {
    public KnowledgeChatAnswerFeedbackCoverageItemResponse {
        answerId = requireNonBlank(answerId, "answerId");
        answerCreatedAt = Objects.requireNonNull(answerCreatedAt, "answerCreatedAt must not be null");
    }

    public static KnowledgeChatAnswerFeedbackCoverageItemResponse from(
            KnowledgeChatAnswerFeedbackCoverageItem item
    ) {
        Objects.requireNonNull(item, "coverage item must not be null");
        if (item.getAnswerId() == null) {
            throw new IllegalArgumentException("persisted coverage answer id must not be null");
        }
        return new KnowledgeChatAnswerFeedbackCoverageItemResponse(
                item.getAnswerId().toString(),
                Objects.requireNonNull(item.getSubmitted(), "submitted must not be null"),
                item.getAnswerCreatedAt()
        );
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
