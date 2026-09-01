package com.agentflow.agent.engine;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/** Safe successful tool output carried into later thinking and final-answer prompts. */
public record AgentObservation(
        String toolCode,
        String summary,
        JsonNode data
) {
    public AgentObservation {
        requireText(toolCode, "toolCode");
        requireText(summary, "summary");
        Objects.requireNonNull(data, "data must not be null");
        data = data.deepCopy();
    }

    @Override
    public JsonNode data() {
        return data.deepCopy();
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
