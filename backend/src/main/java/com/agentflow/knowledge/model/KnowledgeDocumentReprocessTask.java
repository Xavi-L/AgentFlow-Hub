package com.agentflow.knowledge.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

/** Durable V25 cleanup task. Source-storage locators are intentionally absent. */
@TableName("knowledge_document_reprocess_task")
public class KnowledgeDocumentReprocessTask {
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;
    @TableField("user_id")
    private Long userId;
    @TableField("knowledge_base_id")
    private Long knowledgeBaseId;
    @TableField("document_id")
    private Long documentId;
    @TableField("source_vector_generation")
    private Long sourceVectorGeneration;
    @TableField("vectors_deleted_at")
    private OffsetDateTime vectorsDeletedAt;
    @TableField("chunks_deleted_at")
    private OffsetDateTime chunksDeletedAt;
    @TableField("completed_at")
    private OffsetDateTime completedAt;
    @TableField("cleanup_status")
    private String cleanupStatus;
    @TableField("failure_summary")
    private String failureSummary;
    @TableField("retry_count")
    private Integer retryCount;
    @TableField("last_attempted_at")
    private OffsetDateTime lastAttemptedAt;
    @TableField("last_failed_at")
    private OffsetDateTime lastFailedAt;
    @TableField("created_at")
    private OffsetDateTime createdAt;
    @TableField("updated_at")
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(Long knowledgeBaseId) { this.knowledgeBaseId = knowledgeBaseId; }
    public Long getDocumentId() { return documentId; }
    public void setDocumentId(Long documentId) { this.documentId = documentId; }
    public Long getSourceVectorGeneration() { return sourceVectorGeneration; }
    public void setSourceVectorGeneration(Long sourceVectorGeneration) { this.sourceVectorGeneration = sourceVectorGeneration; }
    public OffsetDateTime getVectorsDeletedAt() { return vectorsDeletedAt; }
    public void setVectorsDeletedAt(OffsetDateTime vectorsDeletedAt) { this.vectorsDeletedAt = vectorsDeletedAt; }
    public OffsetDateTime getChunksDeletedAt() { return chunksDeletedAt; }
    public void setChunksDeletedAt(OffsetDateTime chunksDeletedAt) { this.chunksDeletedAt = chunksDeletedAt; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(OffsetDateTime completedAt) { this.completedAt = completedAt; }
    public String getCleanupStatus() { return cleanupStatus; }
    public void setCleanupStatus(String cleanupStatus) { this.cleanupStatus = cleanupStatus; }
    public String getFailureSummary() { return failureSummary; }
    public void setFailureSummary(String failureSummary) { this.failureSummary = failureSummary; }
    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }
    public OffsetDateTime getLastAttemptedAt() { return lastAttemptedAt; }
    public void setLastAttemptedAt(OffsetDateTime lastAttemptedAt) { this.lastAttemptedAt = lastAttemptedAt; }
    public OffsetDateTime getLastFailedAt() { return lastFailedAt; }
    public void setLastFailedAt(OffsetDateTime lastFailedAt) { this.lastFailedAt = lastFailedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
