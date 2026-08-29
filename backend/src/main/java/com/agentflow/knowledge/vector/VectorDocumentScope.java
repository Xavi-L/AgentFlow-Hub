package com.agentflow.knowledge.vector;

/**
 * Server-owned owner, knowledge-base, and document scope for a vector-store document
 * deletion. A bare document identifier is intentionally insufficient at this boundary.
 */
public record VectorDocumentScope(
        long userId,
        long knowledgeBaseId,
        long documentId
) {
    public VectorDocumentScope {
        requirePositive(userId, "userId");
        requirePositive(knowledgeBaseId, "knowledgeBaseId");
        requirePositive(documentId, "documentId");
    }

    private static void requirePositive(long value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
    }
}
