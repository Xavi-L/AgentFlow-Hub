package com.agentflow.agent.rag;

import com.agentflow.agent.trace.RagRetrievalHitRecord;
import java.util.List;
import java.util.Objects;

/** The exact bounded evidence supplied to this execution and retained in its retrieval Trace. */
public record SnapshotRagResult(
        String evidence,
        List<RagRetrievalHitRecord> hits,
        int candidateCount,
        int staleHitCount,
        String embeddingProfileCode
) {
    public SnapshotRagResult {
        Objects.requireNonNull(evidence, "evidence must not be null");
        hits = List.copyOf(hits);
        if (candidateCount < 0 || staleHitCount < 0 || staleHitCount > candidateCount) {
            throw new IllegalArgumentException("invalid retrieval candidate counts");
        }
    }
}
