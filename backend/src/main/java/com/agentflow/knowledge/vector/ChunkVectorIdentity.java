package com.agentflow.knowledge.vector;

import java.util.Objects;
import java.util.UUID;

/** The persisted SHA-256 content hash and the derived, Qdrant-compatible point ID. */
public record ChunkVectorIdentity(String contentHash, String vectorId) {
    public ChunkVectorIdentity {
        Objects.requireNonNull(contentHash, "contentHash must not be null");
        if (!contentHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("contentHash must be a lowercase SHA-256 hex string");
        }
        Objects.requireNonNull(vectorId, "vectorId must not be null");
        UUID.fromString(vectorId);
    }
}
