package com.agentflow.knowledge.vector;

import java.util.List;
import java.util.Objects;

/** A provider-neutral, finite embedding vector. */
public record EmbeddingVector(List<Float> values) {
    public EmbeddingVector {
        values = List.copyOf(Objects.requireNonNull(values, "values must not be null"));
        if (values.isEmpty()) {
            throw new IllegalArgumentException("values must not be empty");
        }
        if (values.stream().anyMatch(value -> value == null || !Float.isFinite(value))) {
            throw new IllegalArgumentException("values must contain only finite floats");
        }
    }
}
