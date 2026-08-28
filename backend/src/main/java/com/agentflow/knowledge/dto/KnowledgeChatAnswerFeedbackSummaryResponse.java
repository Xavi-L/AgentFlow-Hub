package com.agentflow.knowledge.dto;

import com.agentflow.knowledge.model.KnowledgeChatAnswerFeedbackSummary;
import java.util.Objects;

/**
 * 中文：V15 当前 owner、当前知识库不可变回答反馈的原始事件计数概览。响应只包含已提交、
 * HELPFUL 与 NOT_HELPFUL 三个计数；它不是模型质量、准确率或训练结论。
 *
 * <p>English: V15 raw immutable-answer-feedback event counts for the current owner and
 * knowledge base. The response contains only submitted, HELPFUL, and NOT_HELPFUL counts; it is
 * not a model-quality, accuracy, or training conclusion.</p>
 */
public record KnowledgeChatAnswerFeedbackSummaryResponse(
        long submittedCount,
        long helpfulCount,
        long notHelpfulCount
) {
    public KnowledgeChatAnswerFeedbackSummaryResponse {
        if (submittedCount < 0 || helpfulCount < 0 || notHelpfulCount < 0) {
            throw new IllegalArgumentException("feedback summary counts must not be negative");
        }
        if (submittedCount != Math.addExact(helpfulCount, notHelpfulCount)) {
            throw new IllegalArgumentException(
                    "submittedCount must equal helpfulCount plus notHelpfulCount"
            );
        }
    }

    public static KnowledgeChatAnswerFeedbackSummaryResponse from(
            KnowledgeChatAnswerFeedbackSummary summary
    ) {
        Objects.requireNonNull(summary, "feedback summary must not be null");
        return new KnowledgeChatAnswerFeedbackSummaryResponse(
                Objects.requireNonNull(summary.getSubmittedCount(), "submittedCount must not be null"),
                Objects.requireNonNull(summary.getHelpfulCount(), "helpfulCount must not be null"),
                Objects.requireNonNull(summary.getNotHelpfulCount(), "notHelpfulCount must not be null")
        );
    }
}
