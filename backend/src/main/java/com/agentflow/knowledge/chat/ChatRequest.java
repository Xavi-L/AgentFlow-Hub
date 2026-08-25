package com.agentflow.knowledge.chat;

import java.util.Objects;

/**
 * Gateway input deliberately contains no model, prompt, source, chunk, or citation controls.
 * The context must be the exact string returned by V8.
 */
public record ChatRequest(String query, String context, int maxAnswerTokens) {
    public ChatRequest {
        query = requireNonBlank(query, "query");
        context = requireNonBlank(context, "context");
        if (maxAnswerTokens < 1) {
            throw new IllegalArgumentException("maxAnswerTokens must be positive");
        }
    }

    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
