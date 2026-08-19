package com.agentflow.knowledge.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 中文：V8 显式上下文装配验收接口的输入。query/topK 沿用 V7，预算必须由调用方明确给出，
 * 以便后续 V9 Chat 调用能把检索 context 与回答预算清楚分开。
 *
 * <p>English: Input for V8's explicit context-assembly verification endpoint. query/topK
 * reuse V7, while callers must explicitly provide the context budget so a later V9 Chat
 * call can keep retrieval context and answer budgets distinct.
 */
public record RetrieveContextTestRequest(
        @NotBlank(message = "query must not be blank")
        @Size(max = 1_000, message = "query must not exceed 1000 characters")
        String query,

        @Min(value = 1, message = "topK must be between 1 and 10")
        @Max(value = 10, message = "topK must be between 1 and 10")
        Integer topK,

        @NotNull(message = "maxContextTokens is required")
        @Min(value = 1, message = "maxContextTokens must be between 1 and 8000")
        @Max(value = 8_000, message = "maxContextTokens must be between 1 and 8000")
        Integer maxContextTokens
) {
}
