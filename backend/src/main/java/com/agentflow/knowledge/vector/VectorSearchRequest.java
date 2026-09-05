package com.agentflow.knowledge.vector;

import java.util.Objects;
import java.util.List;

/**
 * Provider-neutral dense-vector search request. Scope is mandatory rather than supplied
 * as arbitrary client-controlled payload filters.
 */
public record VectorSearchRequest(
        EmbeddingVector vector,
        long userId,
        long knowledgeBaseId,
        int limit,
        List<DocumentGeneration> documents
) {
    /** Existing V7 callers retain their owner/KB search. Task callers supply exact generations. */
    public VectorSearchRequest(EmbeddingVector vector, long userId, long knowledgeBaseId, int limit) {
        this(vector, userId, knowledgeBaseId, limit, List.of());
    }

    public VectorSearchRequest {
        vector = Objects.requireNonNull(vector, "vector must not be null");
        requirePositive(userId, "userId");
        requirePositive(knowledgeBaseId, "knowledgeBaseId");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        documents = List.copyOf(Objects.requireNonNull(documents, "documents must not be null"));
    }

    public record DocumentGeneration(long documentId, long vectorGeneration) {
        public DocumentGeneration {
            requirePositive(documentId, "documentId");
            if (vectorGeneration < 0) {
                throw new IllegalArgumentException("vectorGeneration must not be negative");
            }
        }
    }

    private static void requirePositive(long value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
    }
}
