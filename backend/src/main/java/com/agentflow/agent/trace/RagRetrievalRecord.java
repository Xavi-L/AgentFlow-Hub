package com.agentflow.agent.trace;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** Validated immutable input for one retrieval row and its complete hit snapshot. */
public record RagRetrievalRecord(
        StepHandle step,
        String query,
        String embeddingProfileCode,
        JsonNode corpusSnapshot,
        int topK,
        BigDecimal similarityThreshold,
        int candidateCount,
        int validHitCount,
        int staleHitCount,
        long latencyMs,
        TraceRecordStatus status,
        String errorCode,
        String errorMessage,
        List<RagRetrievalHitRecord> hits
) {
    public RagRetrievalRecord {
        Objects.requireNonNull(step, "step must not be null");
        if (step.stepType() != StepType.PRE_RETRIEVAL) {
            throw new IllegalArgumentException("RAG retrievals require a PRE_RETRIEVAL step");
        }
        requireText(query, "query");
        requireText(embeddingProfileCode, "embeddingProfileCode");
        Objects.requireNonNull(corpusSnapshot, "corpusSnapshot must not be null");
        if (!corpusSnapshot.isObject()) {
            throw new IllegalArgumentException("corpusSnapshot must be a JSON object");
        }
        corpusSnapshot = corpusSnapshot.deepCopy();
        Objects.requireNonNull(similarityThreshold, "similarityThreshold must not be null");
        if (similarityThreshold.compareTo(BigDecimal.ONE.negate()) < 0
                || similarityThreshold.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("similarityThreshold must be between -1 and 1");
        }
        Objects.requireNonNull(status, "status must not be null");
        hits = List.copyOf(Objects.requireNonNull(hits, "hits must not be null"));
        if (hits.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("hits must not contain null entries");
        }
        if (topK < 1 || candidateCount < 0 || validHitCount < 0 || staleHitCount < 0 || latencyMs < 0) {
            throw new IllegalArgumentException("RAG retrieval counters and latency are invalid");
        }
        if (validHitCount != hits.size()) {
            throw new IllegalArgumentException("validHitCount must equal the persisted hit count");
        }
        if (validHitCount > topK || ((long) validHitCount + staleHitCount) > candidateCount) {
            throw new IllegalArgumentException("RAG retrieval counts are inconsistent");
        }
        if (status == TraceRecordStatus.SUCCESS) {
            if (errorCode != null || errorMessage != null) {
                throw new IllegalArgumentException("Successful RAG retrievals must not contain errors");
            }
        } else {
            requireText(errorCode, "errorCode");
            requireText(errorMessage, "errorMessage");
        }
    }

    @Override
    public JsonNode corpusSnapshot() {
        return corpusSnapshot.deepCopy();
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
