package com.agentflow.agent.trace.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/** Immutable internal aggregate; V39 deliberately exposes no Controller for this view. */
public record TaskTraceView(long taskId, String taskStatus, List<Step> steps) {
    public TaskTraceView {
        if (taskId <= 0) {
            throw new IllegalArgumentException("taskId must be positive");
        }
        Objects.requireNonNull(taskStatus, "taskStatus must not be null");
        steps = copy(steps, "steps");
    }

    public record Step(
            long id,
            int stepIndex,
            String stepType,
            String status,
            String title,
            JsonNode summary,
            String errorCode,
            String errorMessage,
            OffsetDateTime startedAt,
            OffsetDateTime endedAt,
            Long latencyMs,
            OffsetDateTime createdAt,
            List<LlmCall> llmCalls,
            List<RagRetrieval> ragRetrievals,
            List<ToolCall> toolCalls
    ) {
        public Step {
            summary = copyJson(summary, "summary");
            llmCalls = copy(llmCalls, "llmCalls");
            ragRetrievals = copy(ragRetrievals, "ragRetrievals");
            toolCalls = copy(toolCalls, "toolCalls");
        }

        @Override
        public JsonNode summary() {
            return summary.deepCopy();
        }
    }

    public record LlmCall(
            long id,
            String callType,
            String provider,
            String requestedModel,
            String resolvedModel,
            JsonNode requestSnapshot,
            String responseText,
            String finishReason,
            String providerRequestId,
            Integer inputTokens,
            Integer outputTokens,
            Integer totalTokens,
            String usageQuality,
            long latencyMs,
            String status,
            String errorCode,
            String errorMessage,
            OffsetDateTime createdAt
    ) {
        public LlmCall {
            requestSnapshot = copyJson(requestSnapshot, "requestSnapshot");
        }

        @Override
        public JsonNode requestSnapshot() {
            return requestSnapshot.deepCopy();
        }
    }

    public record RagRetrieval(
            long id,
            String query,
            String embeddingProfileCode,
            JsonNode corpusSnapshot,
            int topK,
            BigDecimal similarityThreshold,
            int candidateCount,
            int validHitCount,
            int staleHitCount,
            long latencyMs,
            String status,
            String errorCode,
            String errorMessage,
            OffsetDateTime createdAt,
            List<RagHit> hits
    ) {
        public RagRetrieval {
            corpusSnapshot = copyJson(corpusSnapshot, "corpusSnapshot");
            hits = copy(hits, "hits");
        }

        @Override
        public JsonNode corpusSnapshot() {
            return corpusSnapshot.deepCopy();
        }
    }

    public record RagHit(
            long id,
            int rankNo,
            String citationId,
            long chunkIdSnapshot,
            long documentIdSnapshot,
            long knowledgeBaseIdSnapshot,
            long vectorGeneration,
            BigDecimal score,
            String contentSnapshot,
            JsonNode metadataSnapshot,
            OffsetDateTime createdAt
    ) {
        public RagHit {
            metadataSnapshot = copyJson(metadataSnapshot, "metadataSnapshot");
        }

        @Override
        public JsonNode metadataSnapshot() {
            return metadataSnapshot.deepCopy();
        }
    }

    public record ToolCall(
            long id,
            long toolId,
            String toolCode,
            String toolName,
            JsonNode arguments,
            JsonNode result,
            String status,
            int retryCount,
            Integer latencyMs,
            String errorCode,
            String errorMessage,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            OffsetDateTime createdAt
    ) {
        public ToolCall {
            arguments = copyJson(arguments, "arguments");
            result = result == null ? null : result.deepCopy();
        }

        @Override
        public JsonNode arguments() {
            return arguments.deepCopy();
        }

        @Override
        public JsonNode result() {
            return result == null ? null : result.deepCopy();
        }
    }

    private static JsonNode copyJson(JsonNode value, String field) {
        return Objects.requireNonNull(value, field + " must not be null").deepCopy();
    }

    private static <T> List<T> copy(List<T> values, String field) {
        List<T> result = List.copyOf(Objects.requireNonNull(values, field + " must not be null"));
        if (result.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(field + " must not contain null entries");
        }
        return result;
    }
}
