package com.agentflow.agent.trace;

import com.agentflow.agent.task.model.TokenUsageQuality;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/** Validated immutable input for one completed LLM call Trace row. */
public record LlmCallRecord(
        StepHandle step,
        LlmCallType callType,
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
        TokenUsageQuality usageQuality,
        long latencyMs,
        TraceRecordStatus status,
        String errorCode,
        String errorMessage
) {
    public LlmCallRecord {
        Objects.requireNonNull(step, "step must not be null");
        Objects.requireNonNull(callType, "callType must not be null");
        requireText(provider, "provider");
        requireText(requestedModel, "requestedModel");
        requireOptionalText(resolvedModel, "resolvedModel");
        Objects.requireNonNull(requestSnapshot, "requestSnapshot must not be null");
        if (!requestSnapshot.isObject()) {
            throw new IllegalArgumentException("requestSnapshot must be a JSON object");
        }
        requestSnapshot = requestSnapshot.deepCopy();
        Objects.requireNonNull(usageQuality, "usageQuality must not be null");
        Objects.requireNonNull(status, "status must not be null");
        if (step.stepType() != callType.requiredStepType()) {
            throw new IllegalArgumentException("LLM call type does not match the step type");
        }
        if (latencyMs < 0) {
            throw new IllegalArgumentException("latencyMs must not be negative");
        }
        validateUsage(inputTokens, outputTokens, totalTokens, usageQuality);
        validateOutcome(status, responseText, errorCode, errorMessage);
    }

    @Override
    public JsonNode requestSnapshot() {
        return requestSnapshot.deepCopy();
    }

    private static void validateUsage(
            Integer inputTokens,
            Integer outputTokens,
            Integer totalTokens,
            TokenUsageQuality quality
    ) {
        if (quality == TokenUsageQuality.UNKNOWN) {
            if (inputTokens != null || outputTokens != null || totalTokens != null) {
                throw new IllegalArgumentException("UNKNOWN LLM usage must use three null token fields");
            }
            return;
        }
        if (inputTokens == null || outputTokens == null || totalTokens == null
                || inputTokens < 0 || outputTokens < 0 || totalTokens < 0
                || ((long) inputTokens + outputTokens) != totalTokens.longValue()) {
            throw new IllegalArgumentException("Known LLM usage must be nonnegative and additive");
        }
    }

    private static void validateOutcome(
            TraceRecordStatus status,
            String responseText,
            String errorCode,
            String errorMessage
    ) {
        if (status == TraceRecordStatus.SUCCESS) {
            requireText(responseText, "responseText");
            if (errorCode != null || errorMessage != null) {
                throw new IllegalArgumentException("Successful LLM calls must not contain errors");
            }
            return;
        }
        if (responseText != null) {
            throw new IllegalArgumentException("Failed LLM calls must not persist an untrusted response body");
        }
        requireText(errorCode, "errorCode");
        requireText(errorMessage, "errorMessage");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static void requireOptionalText(String value, String field) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(field + " must be null or non-blank");
        }
    }
}
