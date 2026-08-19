package com.agentflow.knowledge.vector;

import java.util.Objects;
import java.util.UUID;

/**
 * A scored vector-store locator. Its payload is not authoritative; V7 must use the
 * identifiers here to re-read and validate the current chunk from PostgreSQL.
 */
public record VectorSearchHit(
        String vectorId,
        long chunkId,
        double score
) {
    public VectorSearchHit {
        Objects.requireNonNull(vectorId, "vectorId must not be null");
        try {
            UUID.fromString(vectorId);
        } catch (IllegalArgumentException invalidUuid) {
            throw new IllegalArgumentException("vectorId must be a UUID", invalidUuid);
        }
        if (chunkId <= 0) {
            throw new IllegalArgumentException("chunkId must be positive");
        }
        if (!Double.isFinite(score)) {
            throw new IllegalArgumentException("score must be finite");
        }
    }
}
