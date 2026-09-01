package com.agentflow.agent.engine;

import java.util.Objects;

/** Sanitized internal execution failure with no provider, prompt, response, or tool cause. */
public final class AgentExecutionException extends RuntimeException {
    private final AgentFailureType failureType;

    public AgentExecutionException(AgentFailureType failureType, String safeMessage) {
        super(Objects.requireNonNull(safeMessage, "safeMessage must not be null"));
        this.failureType = Objects.requireNonNull(failureType, "failureType must not be null");
    }

    public AgentFailureType failureType() {
        return failureType;
    }
}
