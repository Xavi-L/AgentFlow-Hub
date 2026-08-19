package com.agentflow.knowledge.vector;

import java.util.Objects;

/**
 * Provider-neutral dense-vector search request. Scope is mandatory rather than supplied
 * as arbitrary client-controlled payload filters.
 */
public record VectorSearchRequest(
        EmbeddingVector vector,
        long userId,
        long knowledgeBaseId,
        int limit
) {
    public VectorSearchRequest {
        vector = Objects.requireNonNull(vector, "vector must not be null");
        requirePositive(userId, "userId");
        requirePositive(knowledgeBaseId, "knowledgeBaseId");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
    }

    private static void requirePositive(long value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
    }
}
