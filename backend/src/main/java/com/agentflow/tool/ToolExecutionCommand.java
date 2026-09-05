package com.agentflow.tool;

import com.agentflow.agent.snapshot.AgentTaskExecutionSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Objects;

/** Internal execution identity; task calls must carry the complete creation-time snapshot. */
public record ToolExecutionCommand(
        Long toolId,
        Long taskId,
        Long stepId,
        JsonNode arguments,
        TaskScope taskScope
) {
    public ToolExecutionCommand {
        Objects.requireNonNull(toolId, "toolId must not be null");
        if (toolId <= 0) {
            throw new IllegalArgumentException("toolId must be positive");
        }
        boolean standalone = taskId == null && stepId == null;
        boolean taskScoped = taskId != null && stepId != null;
        if (!standalone && !taskScoped) {
            throw new IllegalArgumentException("taskId and stepId must either both be null or both be present");
        }
        if (taskScoped && (taskId <= 0 || stepId <= 0)) {
            throw new IllegalArgumentException("taskId and stepId must be positive");
        }
        if (standalone && taskScope != null) {
            throw new IllegalArgumentException("Standalone calls cannot carry a task scope");
        }
        if (taskScope != null) {
            arguments = arguments == null ? null : arguments.deepCopy();
        }
    }

    /** Kept for standalone callers and V39 log-only identity fixtures. */
    public ToolExecutionCommand(Long toolId, Long taskId, Long stepId, JsonNode arguments) {
        this(toolId, taskId, stepId, arguments, null);
    }

    @Override
    public JsonNode arguments() {
        return taskScope == null || arguments == null ? arguments : arguments.deepCopy();
    }

    public static ToolExecutionCommand standalone(Long toolId, JsonNode arguments) {
        return new ToolExecutionCommand(toolId, null, null, arguments);
    }

    /** Identity-only commands may be logged, but cannot execute a persistent task. */
    public static ToolExecutionCommand taskScoped(Long toolId, Long taskId, Long stepId, JsonNode arguments) {
        return new ToolExecutionCommand(toolId, taskId, stepId, arguments);
    }

    public static ToolExecutionCommand taskScoped(
            Long toolId, Long taskId, Long stepId, Long userId, Long agentId,
            AgentTaskExecutionSnapshot snapshot, JsonNode arguments,
            Instant deadlineAt, Runnable checkBoundary
    ) {
        return new ToolExecutionCommand(toolId, taskId, stepId, arguments,
                new TaskScope(userId, agentId, snapshot, deadlineAt, checkBoundary));
    }

    public record TaskScope(
            Long userId,
            Long agentId,
            AgentTaskExecutionSnapshot snapshot,
            Instant deadlineAt,
            Runnable checkBoundary
    ) {
        public TaskScope {
            if (userId == null || userId <= 0 || agentId == null || agentId <= 0) {
                throw new IllegalArgumentException("Task owner and agent must be positive");
            }
            Objects.requireNonNull(snapshot, "snapshot must not be null");
            Objects.requireNonNull(deadlineAt, "deadlineAt must not be null");
            Objects.requireNonNull(checkBoundary, "checkBoundary must not be null");
            if (!agentId.toString().equals(snapshot.agent().agentId())) {
                throw new IllegalArgumentException("Task agent must match the execution snapshot");
            }
        }
    }
}
