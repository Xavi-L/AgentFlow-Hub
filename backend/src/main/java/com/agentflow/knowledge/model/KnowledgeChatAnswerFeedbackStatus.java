package com.agentflow.knowledge.model;

import java.time.OffsetDateTime;

/**
 * 中文：V13 由父回答 LEFT JOIN 得到的只读反馈状态投影。answerId 始终来自 scope 内的
 * knowledge_chat_answer；feedbackId 为 null 只表示尚未提交 V12 反馈，并不表示回答不存在。
 *
 * <p>English: Read-only V13 feedback-status projection from a parent-answer LEFT JOIN. The
 * answerId always comes from an in-scope knowledge_chat_answer; a null feedbackId means only
 * that no V12 feedback has been submitted, not that the answer is absent.</p>
 */
public class KnowledgeChatAnswerFeedbackStatus {
    private Long answerId;
    private Long feedbackId;
    private String verdict;
    private OffsetDateTime createdAt;

    public Long getAnswerId() {
        return answerId;
    }

    public void setAnswerId(Long answerId) {
        this.answerId = answerId;
    }

    public Long getFeedbackId() {
        return feedbackId;
    }

    public void setFeedbackId(Long feedbackId) {
        this.feedbackId = feedbackId;
    }

    public String getVerdict() {
        return verdict;
    }

    public void setVerdict(String verdict) {
        this.verdict = verdict;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public boolean hasSubmittedFeedback() {
        return feedbackId != null;
    }
}
