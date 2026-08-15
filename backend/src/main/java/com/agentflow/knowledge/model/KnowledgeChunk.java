package com.agentflow.knowledge.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

/**
 * 中文：{@code knowledge_chunk} 的 Java 映射。每一行是一个文档中按固定顺序生成的、可直接
 * 审阅的文本块；embedding 本身仍不写入 PostgreSQL，但 V5 保存可审阅的向量化状态、内容 hash
 * 和稳定 Qdrant point ID。
 *
 * <p>English: Java mapping for {@code knowledge_chunk}. Each row is an ordered,
 * directly inspectable text block from one document. The embedding itself stays outside
 * PostgreSQL, while V5 records its vectorization state, content hash, and stable Qdrant
 * point ID.
 */
@TableName("knowledge_chunk")
public class KnowledgeChunk {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("knowledge_base_id")
    private Long knowledgeBaseId;

    @TableField("document_id")
    private Long documentId;

    @TableField("chunk_index")
    private Integer chunkIndex;

    private String content;

    @TableField("title_path")
    private String titlePath;

    @TableField("char_count")
    private Integer charCount;

    @TableField("token_count")
    private Integer tokenCount;

    @TableField("vectorization_status")
    private String vectorizationStatus;

    @TableField("vectorization_error")
    private String vectorizationError;

    @TableField("content_hash")
    private String contentHash;

    @TableField("vector_id")
    private String vectorId;

    @TableField("created_at")
    private OffsetDateTime createdAt;

    @TableField("updated_at")
    private OffsetDateTime updatedAt;

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

    public Integer getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(Integer chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getTitlePath() {
        return titlePath;
    }

    public void setTitlePath(String titlePath) {
        this.titlePath = titlePath;
    }

    public Integer getCharCount() {
        return charCount;
    }

    public void setCharCount(Integer charCount) {
        this.charCount = charCount;
    }

    public Integer getTokenCount() {
        return tokenCount;
    }

    public void setTokenCount(Integer tokenCount) {
        this.tokenCount = tokenCount;
    }

    public String getVectorizationStatus() {
        return vectorizationStatus;
    }

    public void setVectorizationStatus(String vectorizationStatus) {
        this.vectorizationStatus = vectorizationStatus;
    }

    public String getVectorizationError() {
        return vectorizationError;
    }

    public void setVectorizationError(String vectorizationError) {
        this.vectorizationError = vectorizationError;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public String getVectorId() {
        return vectorId;
    }

    public void setVectorId(String vectorId) {
        this.vectorId = vectorId;
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
}
