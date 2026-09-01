package com.agentflow.infra.llm;

import java.util.Objects;

/** Text plus provider metadata returned by one completed synchronous chat call. */
public record LlmChatResult(
        String content,
        String resolvedModel,
        String finishReason,
        LlmTokenUsage usage,
        String providerRequestId,
        long latencyMs
) {
    public LlmChatResult {
        Objects.requireNonNull(content, "content must not be null");
        if (content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        Objects.requireNonNull(usage, "usage must not be null");
        if (latencyMs < 0) {
            throw new IllegalArgumentException("latencyMs must not be negative");
        }
    }
}
