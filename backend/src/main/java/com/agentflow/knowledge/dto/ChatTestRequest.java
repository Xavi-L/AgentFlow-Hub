package com.agentflow.knowledge.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 中文：V9 单次 RAG 回答的窄请求边界。局部严格反序列化器只接受本 record 的四个字段，避免
 * 客户端插入模型、Prompt、chunk 或 citation 控制面。
 *
 * <p>English: Narrow V9 input for one RAG answer. Its local strict deserializer accepts
 * only these four fields so clients cannot inject model, prompt, chunk, or citation controls.
 */
@JsonDeserialize(using = ChatTestRequestDeserializer.class)
public record ChatTestRequest(
        @NotBlank(message = "query must not be blank")
        @Size(max = 1_000, message = "query must not exceed 1000 characters")
        String query,

        @Min(value = 1, message = "topK must be between 1 and 10")
        @Max(value = 10, message = "topK must be between 1 and 10")
        Integer topK,

        @NotNull(message = "maxContextTokens is required")
        @Min(value = 1, message = "maxContextTokens must be between 1 and 8000")
        @Max(value = 8_000, message = "maxContextTokens must be between 1 and 8000")
        Integer maxContextTokens,

        @NotNull(message = "maxAnswerTokens is required")
        @Min(value = 1, message = "maxAnswerTokens must be between 1 and 4096")
        @Max(value = 4_096, message = "maxAnswerTokens must be between 1 and 4096")
        Integer maxAnswerTokens
) {
}
