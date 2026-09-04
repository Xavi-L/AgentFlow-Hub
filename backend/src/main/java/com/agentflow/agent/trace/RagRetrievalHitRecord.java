package com.agentflow.agent.trace;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.Objects;

/** Historical retrieval-hit snapshot with deliberately no live source-entity reference. */
public record RagRetrievalHitRecord(
        int rankNo,
        String citationId,
        long chunkIdSnapshot,
        long documentIdSnapshot,
        long knowledgeBaseIdSnapshot,
        long vectorGeneration,
        BigDecimal score,
        String contentSnapshot,
        JsonNode metadataSnapshot
) {
    public RagRetrievalHitRecord {
        if (rankNo < 1 || chunkIdSnapshot <= 0 || documentIdSnapshot <= 0
                || knowledgeBaseIdSnapshot <= 0 || vectorGeneration < 0) {
            throw new IllegalArgumentException("RAG hit rank and snapshot identities are invalid");
        }
        if (citationId == null || citationId.isBlank()) {
            throw new IllegalArgumentException("citationId must not be blank");
        }
        Objects.requireNonNull(score, "score must not be null");
        if (score.compareTo(BigDecimal.ONE.negate()) < 0 || score.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("score must be between -1 and 1");
        }
        if (contentSnapshot == null || contentSnapshot.isBlank()) {
            throw new IllegalArgumentException("contentSnapshot must not be blank");
        }
        Objects.requireNonNull(metadataSnapshot, "metadataSnapshot must not be null");
        if (!metadataSnapshot.isObject()) {
            throw new IllegalArgumentException("metadataSnapshot must be a JSON object");
        }
        metadataSnapshot = metadataSnapshot.deepCopy();
    }

    @Override
    public JsonNode metadataSnapshot() {
        return metadataSnapshot.deepCopy();
    }
}
