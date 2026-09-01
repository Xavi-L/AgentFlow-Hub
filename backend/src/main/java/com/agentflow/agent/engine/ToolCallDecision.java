package com.agentflow.agent.engine;

import com.fasterxml.jackson.databind.JsonNode;

/** A model tool intent already resolved against the execution's safe tool snapshot. */
public record ToolCallDecision(
        Long toolId,
        String toolCode,
        JsonNode arguments,
        String reason
) implements AgentDecision {
    public ToolCallDecision {
        if (toolId == null || toolId <= 0
                || toolCode == null || toolCode.isBlank()
                || arguments == null || !arguments.isObject()
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Invalid tool-call decision");
        }
        arguments = arguments.deepCopy();
    }

    @Override
    public JsonNode arguments() {
        return arguments.deepCopy();
    }
}
