package com.agentflow.tool;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * One request to the unified tool runtime. Administrative/direct calls remain standalone while
 * task execution must provide task and step identity as an inseparable pair.
 */
public record ToolExecutionCommand(
        Long toolId,
        Long taskId,
        Long stepId,
        JsonNode arguments
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
    }

    public static ToolExecutionCommand standalone(Long toolId, JsonNode arguments) {
        return new ToolExecutionCommand(toolId, null, null, arguments);
    }

    public static ToolExecutionCommand taskScoped(
            Long toolId,
            Long taskId,
            Long stepId,
            JsonNode arguments
    ) {
        return new ToolExecutionCommand(toolId, taskId, stepId, arguments);
    }
}
