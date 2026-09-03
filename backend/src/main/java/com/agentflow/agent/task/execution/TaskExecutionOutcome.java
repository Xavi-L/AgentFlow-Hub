package com.agentflow.agent.task.execution;

import com.agentflow.agent.task.model.TaskTerminationReason;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.Objects;

/** Scriptable M4C outcome seam; it is not an AgentEngine result adapter yet. */
public record TaskExecutionOutcome(
        TaskExecutionResultType resultType,
        TaskTerminationReason terminationReason,
        String finalAnswer,
        int decisionTurnsUsed,
        int toolCallsUsed,
        TaskTokenUsage tokenUsage,
        JsonNode citations,
        String errorCode,
        String errorMessage
) {
    public TaskExecutionOutcome {
        Objects.requireNonNull(resultType, "resultType must not be null");
        Objects.requireNonNull(terminationReason, "terminationReason must not be null");
        Objects.requireNonNull(tokenUsage, "tokenUsage must not be null");
        if (decisionTurnsUsed < 0 || toolCallsUsed < 0) {
            throw new IllegalArgumentException("used counters must not be negative");
        }
        citations = citations == null ? JsonNodeFactory.instance.arrayNode() : citations.deepCopy();
        if (!citations.isArray()) {
            throw new IllegalArgumentException("citations must be an array");
        }
        validateResultShape(
                resultType,
                terminationReason,
                finalAnswer,
                citations,
                errorCode,
                errorMessage
        );
    }

    public static TaskExecutionOutcome completed(String finalAnswer) {
        return completed(
                finalAnswer,
                TaskTerminationReason.ANSWERED,
                0,
                0,
                TaskTokenUsage.UNKNOWN_ZERO,
                JsonNodeFactory.instance.arrayNode()
        );
    }

    public static TaskExecutionOutcome completed(
            String finalAnswer,
            TaskTerminationReason terminationReason,
            int decisionTurnsUsed,
            int toolCallsUsed,
            TaskTokenUsage tokenUsage,
            JsonNode citations
    ) {
        return new TaskExecutionOutcome(
                TaskExecutionResultType.COMPLETED,
                terminationReason,
                finalAnswer,
                decisionTurnsUsed,
                toolCallsUsed,
                tokenUsage,
                citations,
                null,
                null
        );
    }

    public static TaskExecutionOutcome failed(String errorCode, String errorMessage) {
        return failed(
                TaskTerminationReason.SYSTEM_ERROR,
                errorCode,
                errorMessage,
                0,
                0,
                TaskTokenUsage.UNKNOWN_ZERO
        );
    }

    public static TaskExecutionOutcome failed(
            TaskTerminationReason terminationReason,
            String errorCode,
            String errorMessage,
            int decisionTurnsUsed,
            int toolCallsUsed,
            TaskTokenUsage tokenUsage
    ) {
        return new TaskExecutionOutcome(
                TaskExecutionResultType.FAILED,
                terminationReason,
                null,
                decisionTurnsUsed,
                toolCallsUsed,
                tokenUsage,
                JsonNodeFactory.instance.arrayNode(),
                errorCode,
                errorMessage
        );
    }

    public static TaskExecutionOutcome timedOut() {
        return timedOut(0, 0, TaskTokenUsage.UNKNOWN_ZERO);
    }

    public static TaskExecutionOutcome timedOut(
            int decisionTurnsUsed,
            int toolCallsUsed,
            TaskTokenUsage tokenUsage
    ) {
        return new TaskExecutionOutcome(
                TaskExecutionResultType.TIMED_OUT,
                TaskTerminationReason.DEADLINE_EXCEEDED,
                null,
                decisionTurnsUsed,
                toolCallsUsed,
                tokenUsage,
                JsonNodeFactory.instance.arrayNode(),
                null,
                null
        );
    }

    public static TaskExecutionOutcome cancelled() {
        return cancelled(0, 0, TaskTokenUsage.UNKNOWN_ZERO);
    }

    public static TaskExecutionOutcome cancelled(
            int decisionTurnsUsed,
            int toolCallsUsed,
            TaskTokenUsage tokenUsage
    ) {
        return new TaskExecutionOutcome(
                TaskExecutionResultType.CANCELLED,
                TaskTerminationReason.USER_CANCELLED,
                null,
                decisionTurnsUsed,
                toolCallsUsed,
                tokenUsage,
                JsonNodeFactory.instance.arrayNode(),
                null,
                null
        );
    }

    @Override
    public JsonNode citations() {
        return citations.deepCopy();
    }

    private static void validateResultShape(
            TaskExecutionResultType resultType,
            TaskTerminationReason terminationReason,
            String finalAnswer,
            JsonNode citations,
            String errorCode,
            String errorMessage
    ) {
        switch (resultType) {
            case COMPLETED -> {
                if (terminationReason != TaskTerminationReason.ANSWERED
                        && terminationReason != TaskTerminationReason.MAX_DECISION_TURNS
                        && terminationReason != TaskTerminationReason.MAX_TOOL_CALLS) {
                    throw new IllegalArgumentException("completed termination reason is invalid");
                }
                requireText(finalAnswer, 0, "finalAnswer");
                if (errorCode != null || errorMessage != null) {
                    throw new IllegalArgumentException("completed outcome must not contain an error");
                }
            }
            case FAILED -> {
                if (terminationReason != TaskTerminationReason.TOKEN_BUDGET_EXHAUSTED
                        && terminationReason != TaskTerminationReason.SYSTEM_ERROR) {
                    throw new IllegalArgumentException("failed termination reason is invalid");
                }
                if (finalAnswer != null || !citations.isEmpty()) {
                    throw new IllegalArgumentException("failed outcome must not contain a result");
                }
                requireText(errorCode, 64, "errorCode");
                requireText(errorMessage, 500, "errorMessage");
            }
            case TIMED_OUT -> requireEmptyResult(
                    terminationReason,
                    TaskTerminationReason.DEADLINE_EXCEEDED,
                    finalAnswer,
                    citations,
                    errorCode,
                    errorMessage
            );
            case CANCELLED -> requireEmptyResult(
                    terminationReason,
                    TaskTerminationReason.USER_CANCELLED,
                    finalAnswer,
                    citations,
                    errorCode,
                    errorMessage
            );
        }
    }

    private static void requireEmptyResult(
            TaskTerminationReason actualReason,
            TaskTerminationReason expectedReason,
            String finalAnswer,
            JsonNode citations,
            String errorCode,
            String errorMessage
    ) {
        if (actualReason != expectedReason
                || finalAnswer != null
                || !citations.isEmpty()
                || errorCode != null
                || errorMessage != null) {
            throw new IllegalArgumentException("terminal outcome shape is invalid");
        }
    }

    private static void requireText(String value, int maxLength, String field) {
        if (value == null || value.isBlank() || (maxLength > 0 && value.length() > maxLength)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }
}
