package com.agentflow.knowledge.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

/**
 * 中文：V12 对一条不可变回答提交的一次不可修改二元反馈。所属 owner 与知识库由 answer_id 指向的
 * V10 回答确定，因此本表不重复可不一致的 user_id 或 knowledge_base_id。
 *
 * <p>English: One immutable V12 binary feedback event for an immutable answer. The owner and
 * knowledge base are determined by the V10 answer referenced by answer_id, so this table does
 * not duplicate user_id or knowledge_base_id fields that could become inconsistent.</p>
 */
@TableName("knowledge_chat_answer_feedback")
public class KnowledgeChatAnswerFeedback {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("answer_id")
    private Long answerId;

    private String verdict;

    @TableField("created_at")
    private OffsetDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getAnswerId() {
        return answerId;
    }

    public void setAnswerId(Long answerId) {
        this.answerId = answerId;
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
}
