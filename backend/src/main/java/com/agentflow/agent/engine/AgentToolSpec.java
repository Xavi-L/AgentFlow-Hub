package com.agentflow.agent.engine;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/** Safe model-facing projection of one ACTIVE tool definition. */
public record AgentToolSpec(
        Long toolId,
        String toolCode,
        String name,
        String description,
        JsonNode inputSchema
) {
    public AgentToolSpec {
        if (toolId == null || toolId <= 0) {
            throw new IllegalArgumentException("toolId must be positive");
        }
        requireText(toolCode, "toolCode");
        requireText(name, "name");
        Objects.requireNonNull(description, "description must not be null");
        if (inputSchema == null || !inputSchema.isObject()) {
            throw new IllegalArgumentException("inputSchema must be an object");
        }
        inputSchema = inputSchema.deepCopy();
    }

    @Override
    public JsonNode inputSchema() {
        return inputSchema.deepCopy();
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
