package com.agentflow.agent.task.execution;

import com.agentflow.agent.snapshot.AgentTaskExecutionSnapshot;
import java.time.Instant;
import java.util.Objects;

public record TaskExecutionRequest(
        long taskId,
        long userId,
        long agentId,
        String userInput,
        AgentTaskExecutionSnapshot executionSnapshot,
        Instant deadlineAt,
        TaskCancellationProbe cancellationProbe
) {
    public TaskExecutionRequest {
        if (taskId <= 0 || userId <= 0 || agentId <= 0) {
            throw new IllegalArgumentException("task, user, and Agent IDs must be positive");
        }
        Objects.requireNonNull(userInput, "userInput must not be null");
        Objects.requireNonNull(executionSnapshot, "executionSnapshot must not be null");
        Objects.requireNonNull(deadlineAt, "deadlineAt must not be null");
        Objects.requireNonNull(cancellationProbe, "cancellationProbe must not be null");
    }
}
