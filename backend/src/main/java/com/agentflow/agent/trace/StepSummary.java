package com.agentflow.agent.trace;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/** Small structured semantic summary; specialized payloads belong in their own log tables. */
public record StepSummary(JsonNode value) {
    public StepSummary {
        Objects.requireNonNull(value, "value must not be null");
        if (!value.isObject()) {
            throw new IllegalArgumentException("Step summary must be a JSON object");
        }
        value = value.deepCopy();
    }

    @Override
    public JsonNode value() {
        return value.deepCopy();
    }
}
