package com.agentflow.knowledge.vector;

import java.util.Objects;

/** Provider/model metadata travels with the content so a later adapter can route safely. */
public record EmbeddingRequest(
        String content,
        String provider,
        String model
) {
    public EmbeddingRequest {
        content = requireNonBlank(content, "content");
        provider = requireNonBlank(provider, "provider");
        model = requireNonBlank(model, "model");
    }

    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
