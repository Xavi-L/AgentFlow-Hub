package com.agentflow.knowledge.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 中文：创建知识库时允许客户端提供的字段。所属用户、状态、扩展 metadata 和审计字段都由服务端
 * 控制，不能由请求体伪造。
 *
 * <p>English: Fields a client may provide when creating a knowledge base. Ownership,
 * status, extensible metadata, and audit fields remain server controlled and cannot be
 * forged in the request body.
 */
public record CreateKnowledgeBaseRequest(
        @NotBlank(message = "name must not be blank")
        @Size(max = 128, message = "name must not exceed 128 characters")
        String name,

        @Size(max = 4_000, message = "description must not exceed 4000 characters")
        String description,

        @Size(max = 64, message = "embeddingProvider must not exceed 64 characters")
        String embeddingProvider,

        @Size(max = 128, message = "embeddingModel must not exceed 128 characters")
        String embeddingModel,

        @Min(value = 80, message = "chunkSize must be at least 80")
        @Max(value = 1000, message = "chunkSize must not exceed 1000")
        Integer chunkSize,

        @Min(value = 0, message = "chunkOverlap must not be negative")
        Integer chunkOverlap
) {
}
