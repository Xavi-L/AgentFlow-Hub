package com.agentflow.agent.trace.dto;

import com.agentflow.agent.task.dto.AgentTaskResponse;
import com.agentflow.agent.task.dto.TaskConfigurationResponse;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.agentflow.agent.task.dto.SafeTaskEventResponse;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/** Public, immutable Trace aggregate. All fields come from one database read snapshot. */
public record PublicTaskTraceResponse(
        AgentTaskResponse task, JsonNode executionSnapshot, List<Step> steps,
        List<SafeTaskEventResponse> events
) {
    @JsonProperty("configuration")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public TaskConfigurationResponse configuration() { return task.configuration(); }

    public PublicTaskTraceResponse {
        Objects.requireNonNull(task, "task must not be null");
        executionSnapshot = copyJson(executionSnapshot, "executionSnapshot");
        steps = copy(steps, "steps");
        events = copy(events, "events");
    }

    @Override
    public JsonNode executionSnapshot() {
        return executionSnapshot.deepCopy();
    }

    public record Step(
            String id,
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
            String id,
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
            String id,
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
            String id,
            int rankNo,
            String citationId,
            String chunkIdSnapshot,
            String documentIdSnapshot,
            String knowledgeBaseIdSnapshot,
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
            String id,
            String toolId,
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
