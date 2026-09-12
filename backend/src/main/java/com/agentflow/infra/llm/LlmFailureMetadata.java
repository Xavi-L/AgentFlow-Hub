package com.agentflow.infra.llm;

import java.util.Objects;

/** Response diagnostics only: never includes response text, reasoning, endpoint, or credentials. */
public record LlmFailureMetadata(
        String resolvedModel,
        String finishReason,
        LlmTokenUsage usage,
        String providerRequestId,
        long latencyMs
) {
    public LlmFailureMetadata {
        Objects.requireNonNull(usage, "usage must not be null");
        if (latencyMs < 0) {
            throw new IllegalArgumentException("latencyMs must not be negative");
        }
    }
}
