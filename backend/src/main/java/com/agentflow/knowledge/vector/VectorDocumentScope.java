package com.agentflow.knowledge.vector;

/**
 * Server-owned vector deletion scope. V24 uses the three-part constructor to delete every
 * generation; V25 uses the factory to fence cleanup to one old generation.
 */
public record VectorDocumentScope(
        long userId,
        long knowledgeBaseId,
        long documentId,
        Long vectorGeneration,
        boolean includeLegacyMissingGeneration
) {
    public VectorDocumentScope(long userId, long knowledgeBaseId, long documentId) {
        this(userId, knowledgeBaseId, documentId, null, false);
    }

    public VectorDocumentScope {
        requirePositive(userId, "userId");
        requirePositive(knowledgeBaseId, "knowledgeBaseId");
        requirePositive(documentId, "documentId");
        if (vectorGeneration != null && vectorGeneration < 0) {
            throw new IllegalArgumentException("vectorGeneration must not be negative");
        }
        if (includeLegacyMissingGeneration && !Long.valueOf(0L).equals(vectorGeneration)) {
            throw new IllegalArgumentException("Legacy missing generation is valid only for generation zero");
        }
    }

    public static VectorDocumentScope forGenerationCleanup(
            long userId,
            long knowledgeBaseId,
            long documentId,
            long vectorGeneration
    ) {
        if (vectorGeneration < 0) {
            throw new IllegalArgumentException("vectorGeneration must not be negative");
        }
        return new VectorDocumentScope(
                userId, knowledgeBaseId, documentId, vectorGeneration, vectorGeneration == 0
        );
    }

    public boolean generationFenced() {
        return vectorGeneration != null;
    }

    private static void requirePositive(long value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
    }
}
