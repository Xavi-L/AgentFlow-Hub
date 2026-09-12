package com.agentflow.infra.llm;

import java.util.Objects;

/**
 * Sanitized LLM failure. Provider bodies, credentials, and endpoint URLs are intentionally
 * excluded from both this exception's message and cause chain.
 */
public final class LlmGatewayException extends RuntimeException {
    private final LlmFailureType failureType;
    private final LlmFailureMetadata metadata;

    public LlmGatewayException(LlmFailureType failureType, String safeMessage) {
        this(failureType, safeMessage, null);
    }

    public LlmGatewayException(LlmFailureType failureType, String safeMessage, LlmFailureMetadata metadata) {
        super(Objects.requireNonNull(safeMessage, "safeMessage must not be null"));
        this.failureType = Objects.requireNonNull(failureType, "failureType must not be null");
        this.metadata = metadata;
    }

    public LlmFailureType failureType() {
        return failureType;
    }

    public LlmFailureMetadata metadata() {
        return metadata;
    }
}
