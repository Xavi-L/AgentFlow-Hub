package com.agentflow.knowledge.model;

/**
 * 中文：V16 从 scope 内不可变 parent answer 聚合出的内部只读覆盖原始计数投影。它保留未提交
 * feedback 的回答，不是比例、模型质量、准确率、趋势或训练结论。
 *
 * <p>English: Internal V16 read-only raw coverage-count projection aggregated from scoped
 * immutable parent answers. It retains answers without submitted feedback; it is not a rate,
 * model-quality, accuracy, trend, or training conclusion.</p>
 */
public class KnowledgeChatAnswerFeedbackCoverage {
    private Long answerCount;
    private Long submittedCount;
    private Long unsubmittedCount;

    public Long getAnswerCount() {
        return answerCount;
    }

    public void setAnswerCount(Long answerCount) {
        this.answerCount = answerCount;
    }

    public Long getSubmittedCount() {
        return submittedCount;
    }

    public void setSubmittedCount(Long submittedCount) {
        this.submittedCount = submittedCount;
    }

    public Long getUnsubmittedCount() {
        return unsubmittedCount;
    }

    public void setUnsubmittedCount(Long unsubmittedCount) {
        this.unsubmittedCount = unsubmittedCount;
    }
}
