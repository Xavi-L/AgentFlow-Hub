package com.agentflow.agent.task.execution;

import com.agentflow.agent.task.model.TokenUsageQuality;
import java.util.Objects;

public record TaskTokenUsage(int inputTokens, int outputTokens, TokenUsageQuality quality) {
    public static final TaskTokenUsage UNKNOWN_ZERO = new TaskTokenUsage(0, 0, TokenUsageQuality.UNKNOWN);

    public TaskTokenUsage {
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("token usage must not be negative");
        }
        Objects.requireNonNull(quality, "quality must not be null");
        Math.addExact(inputTokens, outputTokens);
    }

    public int totalTokens() {
        return Math.addExact(inputTokens, outputTokens);
    }
}
