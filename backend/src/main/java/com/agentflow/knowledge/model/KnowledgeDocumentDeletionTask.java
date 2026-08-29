package com.agentflow.knowledge.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

/**
 * 中文：V24 跨向量库、受控文件存储和 PostgreSQL chunk 清理的持久补偿台账。文档元数据
 * 保持软删除；本表只保存服务器确定的 scope、源文件定位快照和每一步的完成证据。
 *
 * <p>English: V24's durable compensation ledger across the vector store, controlled source
 * storage, and PostgreSQL chunk cleanup. The document metadata remains soft-deleted; this row
 * contains only server-derived scope, a source locator snapshot, and completion evidence.
 */
@TableName("knowledge_document_deletion_task")
public class KnowledgeDocumentDeletionTask {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("knowledge_base_id")
    private Long knowledgeBaseId;

    @TableField("document_id")
    private Long documentId;

    @TableField("storage_bucket")
    private String storageBucket;

    @TableField("storage_object_key")
    private String storageObjectKey;

    @TableField("vectors_deleted_at")
    private OffsetDateTime vectorsDeletedAt;

    @TableField("source_deleted_at")
    private OffsetDateTime sourceDeletedAt;

    @TableField("chunks_deleted_at")
    private OffsetDateTime chunksDeletedAt;

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

    @TableField("completed_at")
    private OffsetDateTime completedAt;

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

    public Long getDocumentId() {
        return documentId;
    }

    public void setDocumentId(Long documentId) {
        this.documentId = documentId;
    }

    public String getStorageBucket() {
        return storageBucket;
    }

    public void setStorageBucket(String storageBucket) {
        this.storageBucket = storageBucket;
    }

    public String getStorageObjectKey() {
        return storageObjectKey;
    }

    public void setStorageObjectKey(String storageObjectKey) {
        this.storageObjectKey = storageObjectKey;
    }

    public OffsetDateTime getVectorsDeletedAt() {
        return vectorsDeletedAt;
    }

    public void setVectorsDeletedAt(OffsetDateTime vectorsDeletedAt) {
        this.vectorsDeletedAt = vectorsDeletedAt;
    }

    public OffsetDateTime getSourceDeletedAt() {
        return sourceDeletedAt;
    }

    public void setSourceDeletedAt(OffsetDateTime sourceDeletedAt) {
        this.sourceDeletedAt = sourceDeletedAt;
    }

    public OffsetDateTime getChunksDeletedAt() {
        return chunksDeletedAt;
    }

    public void setChunksDeletedAt(OffsetDateTime chunksDeletedAt) {
        this.chunksDeletedAt = chunksDeletedAt;
    }

    public String getFailureSummary() {
        return failureSummary;
    }

    public void setFailureSummary(String failureSummary) {
        this.failureSummary = failureSummary;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public OffsetDateTime getLastAttemptedAt() {
        return lastAttemptedAt;
    }

    public void setLastAttemptedAt(OffsetDateTime lastAttemptedAt) {
        this.lastAttemptedAt = lastAttemptedAt;
    }

    public OffsetDateTime getLastFailedAt() {
        return lastFailedAt;
    }

    public void setLastFailedAt(OffsetDateTime lastFailedAt) {
        this.lastFailedAt = lastFailedAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public OffsetDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(OffsetDateTime completedAt) {
        this.completedAt = completedAt;
    }
}
