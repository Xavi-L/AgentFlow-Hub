package com.agentflow.knowledge.vector;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A provider-neutral point ready for an idempotent vector-store upsert. PostgreSQL
 * remains the authority for the chunk body, so payload is limited to retrieval filters
 * and traceable identifiers.
 */
public record VectorStoreRecord(
        String vectorId,
        EmbeddingVector vector,
        Map<String, Object> payload
) {
    public VectorStoreRecord {
        Objects.requireNonNull(vectorId, "vectorId must not be null");
        try {
            UUID.fromString(vectorId);
        } catch (IllegalArgumentException invalidUuid) {
            throw new IllegalArgumentException("vectorId must be a UUID", invalidUuid);
        }
        vector = Objects.requireNonNull(vector, "vector must not be null");
        payload = Map.copyOf(Objects.requireNonNull(payload, "payload must not be null"));
    }
}
