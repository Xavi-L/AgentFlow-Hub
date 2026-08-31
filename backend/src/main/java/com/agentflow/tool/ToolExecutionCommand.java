package com.agentflow.tool;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * One request to the unified tool runtime. Standalone V27 tests deliberately leave task and step
 * IDs null because those parent tables belong to a later slice.
 */
public record ToolExecutionCommand(
        Long toolId,
        Long taskId,
        Long stepId,
        JsonNode arguments
) {
    public ToolExecutionCommand {
        Objects.requireNonNull(toolId, "toolId must not be null");
    }

    public static ToolExecutionCommand standalone(Long toolId, JsonNode arguments) {
        return new ToolExecutionCommand(toolId, null, null, arguments);
    }
}
