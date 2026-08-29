package com.agentflow.knowledge.dto;

import com.agentflow.knowledge.model.KnowledgeChatAnswerFeedbackCoverage;
import java.util.Objects;

/**
 * 中文：V16 当前 owner、当前知识库的不可变回答反馈覆盖原始计数。响应严格只包含回答总数、
 * 已提交数和未提交数；不是比例、质量、准确率或训练结论。
 *
 * <p>English: V16 raw immutable-answer-feedback coverage counts for the current owner and
 * knowledge base. The response contains only answer, submitted, and unsubmitted counts; it is
 * not a rate, quality, accuracy, or training conclusion.</p>
 */
public record KnowledgeChatAnswerFeedbackCoverageResponse(
        long answerCount,
        long submittedCount,
        long unsubmittedCount
) {
    public KnowledgeChatAnswerFeedbackCoverageResponse {
        if (answerCount < 0 || submittedCount < 0 || unsubmittedCount < 0) {
            throw new IllegalArgumentException("feedback coverage counts must not be negative");
        }
        if (answerCount != Math.addExact(submittedCount, unsubmittedCount)) {
            throw new IllegalArgumentException(
                    "answerCount must equal submittedCount plus unsubmittedCount"
            );
        }
    }

    public static KnowledgeChatAnswerFeedbackCoverageResponse from(
            KnowledgeChatAnswerFeedbackCoverage coverage
    ) {
        Objects.requireNonNull(coverage, "feedback coverage must not be null");
        return new KnowledgeChatAnswerFeedbackCoverageResponse(
                Objects.requireNonNull(coverage.getAnswerCount(), "answerCount must not be null"),
                Objects.requireNonNull(
                        coverage.getSubmittedCount(),
                        "submittedCount must not be null"
                ),
                Objects.requireNonNull(
                        coverage.getUnsubmittedCount(),
                        "unsubmittedCount must not be null"
                )
        );
    }
}
