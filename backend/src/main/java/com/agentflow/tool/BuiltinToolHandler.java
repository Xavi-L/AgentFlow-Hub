package com.agentflow.tool;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/** Code-owned implementation boundary for one allowlisted built-in tool. */
public interface BuiltinToolHandler {
    HandlerResult execute(JsonNode arguments);

    record HandlerResult(String summary, JsonNode data) {
        public HandlerResult {
            Objects.requireNonNull(summary, "summary must not be null");
            Objects.requireNonNull(data, "data must not be null");
        }
    }
}
