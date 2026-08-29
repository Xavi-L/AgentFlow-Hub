package com.agentflow.knowledge.model;

import java.time.OffsetDateTime;

/**
 * 中文：V17 当前 owner、当前知识库中一条不可变回答的反馈提交状态分页投影。它只携带回答 ID、
 * 是否已经存在 V12 feedback，以及回答自己的创建时间，不复制反馈事件详情或回答正文。
 *
 * <p>English: One V17 paged submission-state projection for an immutable answer in the current
 * owner's knowledge-base scope. It carries only the answer ID, whether a V12 feedback event
 * exists, and the answer's own creation time; it does not copy feedback details or answer text.</p>
 */
public class KnowledgeChatAnswerFeedbackCoverageItem {
    private Long answerId;
    private Boolean submitted;
    private OffsetDateTime answerCreatedAt;

    public Long getAnswerId() {
        return answerId;
    }

    public void setAnswerId(Long answerId) {
        this.answerId = answerId;
    }

    public Boolean getSubmitted() {
        return submitted;
    }

    public void setSubmitted(Boolean submitted) {
        this.submitted = submitted;
    }

    public OffsetDateTime getAnswerCreatedAt() {
        return answerCreatedAt;
    }

    public void setAnswerCreatedAt(OffsetDateTime answerCreatedAt) {
        this.answerCreatedAt = answerCreatedAt;
    }
}
