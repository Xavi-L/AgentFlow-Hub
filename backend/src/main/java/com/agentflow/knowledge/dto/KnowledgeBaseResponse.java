package com.agentflow.knowledge.dto;

import com.agentflow.knowledge.model.KnowledgeBase;
import com.agentflow.knowledge.readiness.KnowledgeReadConfiguration;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/**
 * 中文：安全的知识库 API 输出。故意不暴露 userId、metadata 和 deletedAt，避免调用者伪造归属
 * 或依赖尚未公开的内部扩展字段。
 *
 * <p>English: Safe knowledge-base API output. userId, metadata, and deletedAt are
 * deliberately not exposed, preventing ownership forgery and reliance on internal
 * extension fields that are not public yet.
 */
public record KnowledgeBaseResponse(
        String id,
        String name,
        String description,
        String embeddingProvider,
        String embeddingModel,
        Integer chunkSize,
        Integer chunkOverlap,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        @JsonInclude(JsonInclude.Include.ALWAYS) String embeddingProfileCode,
        @JsonInclude(JsonInclude.Include.ALWAYS) String chunkStrategyVersion
) {
    /** Preserves existing callers while deriving the added fields from their real configuration. */
    public KnowledgeBaseResponse(
            String id,
            String name,
            String description,
            String embeddingProvider,
            String embeddingModel,
            Integer chunkSize,
            Integer chunkOverlap,
            String status,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
        this(id, name, description, embeddingProvider, embeddingModel, chunkSize, chunkOverlap,
                status, createdAt, updatedAt,
                KnowledgeReadConfiguration.embeddingProfileCode(embeddingProvider, embeddingModel),
                KnowledgeReadConfiguration.chunkStrategyVersion(chunkSize, chunkOverlap));
    }

    /**
     * 中文：BIGINT ID 对外转为字符串，避免 JavaScript Number 的精度丢失。
     * English: Converts the BIGINT ID to a string for clients, avoiding JavaScript Number
     * precision loss.
     */
    public static KnowledgeBaseResponse from(KnowledgeBase knowledgeBase) {
        return new KnowledgeBaseResponse(
                String.valueOf(knowledgeBase.getId()),
                knowledgeBase.getName(),
                knowledgeBase.getDescription(),
                knowledgeBase.getEmbeddingProvider(),
                knowledgeBase.getEmbeddingModel(),
                knowledgeBase.getChunkSize(),
                knowledgeBase.getChunkOverlap(),
                knowledgeBase.getStatus(),
                knowledgeBase.getCreatedAt(),
                knowledgeBase.getUpdatedAt()
        );
    }
}
