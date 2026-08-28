package com.agentflow.knowledge.model;

/**
 * 中文：V15 从已提交不可变 feedback event 聚合出的内部只读投影。它只保存当前 owner、当前
 * 知识库范围内的原始事件计数，不是模型质量、准确率、覆盖率或训练结论。
 *
 * <p>English: Internal V15 read-only projection aggregated from submitted immutable feedback
 * events. It carries only raw event counts in the current owner's knowledge-base scope; it is
 * not a model-quality, accuracy, coverage, or training conclusion.</p>
 */
public class KnowledgeChatAnswerFeedbackSummary {
    private Long submittedCount;
    private Long helpfulCount;
    private Long notHelpfulCount;

    public Long getSubmittedCount() {
        return submittedCount;
    }

    public void setSubmittedCount(Long submittedCount) {
        this.submittedCount = submittedCount;
    }

    public Long getHelpfulCount() {
        return helpfulCount;
    }

    public void setHelpfulCount(Long helpfulCount) {
        this.helpfulCount = helpfulCount;
    }

    public Long getNotHelpfulCount() {
        return notHelpfulCount;
    }

    public void setNotHelpfulCount(Long notHelpfulCount) {
        this.notHelpfulCount = notHelpfulCount;
    }
}
