package com.agentflow.knowledge.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

/**
 * 中文：V10 中一条已完成 citation 校验的单次回答审计快照。它没有 updatedAt 或 deletedAt：记录
 * 只能写入一次，后续查询只能读取该时刻冻结的 answer、预算与来源 JSON。
 *
 * <p>English: One V10 audit snapshot for a citation-validated, single-turn answer. It has
 * no updatedAt or deletedAt: the row is written once, and later reads use only its frozen
 * answer, budgets, and source JSON.</p>
 */
@TableName("knowledge_chat_answer")
public class KnowledgeChatAnswer {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("knowledge_base_id")
    private Long knowledgeBaseId;

    private String query;
    private String answer;

    @TableField("top_k")
    private Integer topK;

    @TableField("max_context_tokens")
    private Integer maxContextTokens;

    @TableField("used_context_tokens")
    private Integer usedContextTokens;

    @TableField("skipped_chunk_count")
    private Integer skippedChunkCount;

    @TableField("max_answer_tokens")
    private Integer maxAnswerTokens;

    @TableField("sources_snapshot_json")
    private String sourcesSnapshotJson;

    @TableField("citation_ids_json")
    private String citationIdsJson;

    @TableField("created_at")
    private OffsetDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getKnowledgeBaseId() {
        return knowledgeBaseId;
    }

    public void setKnowledgeBaseId(Long knowledgeBaseId) {
        this.knowledgeBaseId = knowledgeBaseId;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public Integer getTopK() {
        return topK;
    }

    public void setTopK(Integer topK) {
        this.topK = topK;
    }

    public Integer getMaxContextTokens() {
        return maxContextTokens;
    }

    public void setMaxContextTokens(Integer maxContextTokens) {
        this.maxContextTokens = maxContextTokens;
    }

    public Integer getUsedContextTokens() {
        return usedContextTokens;
    }

    public void setUsedContextTokens(Integer usedContextTokens) {
        this.usedContextTokens = usedContextTokens;
    }

    public Integer getSkippedChunkCount() {
        return skippedChunkCount;
    }

    public void setSkippedChunkCount(Integer skippedChunkCount) {
        this.skippedChunkCount = skippedChunkCount;
    }

    public Integer getMaxAnswerTokens() {
        return maxAnswerTokens;
    }

    public void setMaxAnswerTokens(Integer maxAnswerTokens) {
        this.maxAnswerTokens = maxAnswerTokens;
    }

    public String getSourcesSnapshotJson() {
        return sourcesSnapshotJson;
    }

    public void setSourcesSnapshotJson(String sourcesSnapshotJson) {
        this.sourcesSnapshotJson = sourcesSnapshotJson;
    }

    public String getCitationIdsJson() {
        return citationIdsJson;
    }

    public void setCitationIdsJson(String citationIdsJson) {
        this.citationIdsJson = citationIdsJson;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
