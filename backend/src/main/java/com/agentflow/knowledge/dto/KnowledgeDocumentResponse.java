package com.agentflow.knowledge.dto;

import com.agentflow.knowledge.model.KnowledgeDocument;
import com.agentflow.knowledge.readiness.DocumentReadinessRow;
import com.agentflow.knowledge.readiness.RetrievalReadiness;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/**
 * 中文：文档接入 API 的安全输出。物理存储桶、对象键、用户 ID 和解析错误都是服务端内部信息，
 * 不向调用者暴露。
 *
 * <p>English: Safe API output for document ingestion. The storage bucket, object key,
 * user ID, and parser error are internal server details and are not exposed to clients.
 */
public record KnowledgeDocumentResponse(
        String id,
        String knowledgeBaseId,
        String fileName,
        String fileType,
        Long fileSize,
        String parseStatus,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long vectorGeneration,
        @JsonInclude(JsonInclude.Include.NON_NULL) DocumentVectorizationResponse vectorization,
        @JsonInclude(JsonInclude.Include.NON_NULL) RetrievalReadiness retrievalReadiness
) {
    /** Existing write acknowledgements keep their original shape; GET enriches from a DB snapshot. */
    public KnowledgeDocumentResponse(String id, String knowledgeBaseId, String fileName, String fileType,
            Long fileSize, String parseStatus, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        this(id, knowledgeBaseId, fileName, fileType, fileSize, parseStatus, createdAt, updatedAt,
                null, null, null);
    }

    public static KnowledgeDocumentResponse from(KnowledgeDocument document, DocumentReadinessRow readiness) {
        return new KnowledgeDocumentResponse(
                String.valueOf(document.getId()), String.valueOf(document.getKnowledgeBaseId()),
                document.getFileName(), document.getFileType(), document.getFileSize(), document.getParseStatus(),
                document.getCreatedAt(), document.getUpdatedAt(), document.getVectorGeneration(),
                readiness.vectorization(), readiness.readiness(document.getParseStatus())
        );
    }

    public static KnowledgeDocumentResponse from(KnowledgeDocument document) {
        return new KnowledgeDocumentResponse(
                String.valueOf(document.getId()),
                String.valueOf(document.getKnowledgeBaseId()),
                document.getFileName(),
                document.getFileType(),
                document.getFileSize(),
                document.getParseStatus(),
                document.getCreatedAt(),
                document.getUpdatedAt()
        );
    }
}
