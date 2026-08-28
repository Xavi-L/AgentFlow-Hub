package com.agentflow.knowledge.dto;

import com.agentflow.knowledge.model.KnowledgeChatAnswerFeedback;
import com.agentflow.knowledge.model.KnowledgeChatAnswerFeedbackVerdict;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 中文：V12 不可变反馈事件的窄响应。它不返回回答正文、sources、预算、统计或任何模型评测结论。
 *
 * <p>English: Narrow response for an immutable V12 feedback event. It returns no answer text,
 * sources, budgets, aggregates, or model-evaluation conclusion.</p>
 */
public record KnowledgeChatAnswerFeedbackResponse(
        String feedbackId,
        String answerId,
        KnowledgeChatAnswerFeedbackVerdict verdict,
        OffsetDateTime createdAt
) {
    public KnowledgeChatAnswerFeedbackResponse {
        feedbackId = requireNonBlank(feedbackId, "feedbackId");
        answerId = requireNonBlank(answerId, "answerId");
        verdict = Objects.requireNonNull(verdict, "verdict must not be null");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public static KnowledgeChatAnswerFeedbackResponse from(KnowledgeChatAnswerFeedback feedback) {
        Objects.requireNonNull(feedback, "feedback must not be null");
        if (feedback.getId() == null) {
            throw new IllegalArgumentException("persisted feedback id must not be null");
        }
        if (feedback.getAnswerId() == null) {
            throw new IllegalArgumentException("persisted feedback answer id must not be null");
        }
        if (feedback.getCreatedAt() == null) {
            throw new IllegalArgumentException("persisted feedback createdAt must not be null");
        }
        try {
            return new KnowledgeChatAnswerFeedbackResponse(
                    feedback.getId().toString(),
                    feedback.getAnswerId().toString(),
                    KnowledgeChatAnswerFeedbackVerdict.valueOf(feedback.getVerdict()),
                    feedback.getCreatedAt()
            );
        } catch (IllegalArgumentException invalidPersistedVerdict) {
            throw new IllegalStateException("Persisted chat-answer feedback verdict is invalid", invalidPersistedVerdict);
        }
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
