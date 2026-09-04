package com.agentflow.agent.trace;

import com.agentflow.agent.task.model.TaskEventType;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/** Immutable input for delegating a task event to the existing sequence allocator. */
public record TaskEventRecord(TaskEventType eventType, JsonNode payload) {
    public TaskEventRecord {
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        if (!payload.isObject()) {
            throw new IllegalArgumentException("Task event payload must be a JSON object");
        }
        payload = payload.deepCopy();
    }

    @Override
    public JsonNode payload() {
        return payload.deepCopy();
    }
}
