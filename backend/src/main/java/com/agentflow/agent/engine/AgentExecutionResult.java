package com.agentflow.agent.engine;

import java.util.Objects;

/** Final text and measured in-memory budget usage for one completed execution. */
public record AgentExecutionResult(
        String finalAnswer,
        int stepsUsed,
        int toolCallsUsed,
        long inputTokens,
        long outputTokens,
        long totalTokens
) {
    public AgentExecutionResult {
        Objects.requireNonNull(finalAnswer, "finalAnswer must not be null");
        if (finalAnswer.isBlank()) {
            throw new IllegalArgumentException("finalAnswer must not be blank");
        }
        if (stepsUsed < 0 || toolCallsUsed < 0
                || inputTokens < 0 || outputTokens < 0 || totalTokens < 0) {
            throw new IllegalArgumentException("execution usage must not be negative");
        }
    }
}
