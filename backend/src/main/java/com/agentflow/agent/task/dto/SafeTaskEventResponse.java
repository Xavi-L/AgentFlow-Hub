package com.agentflow.agent.task.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.Objects;

/** Public event shape shared by Trace and future event replay. Sequence numbers remain numeric. */
public record SafeTaskEventResponse(
        String id, String taskId, long sequenceNo, String eventType, JsonNode payload,
        OffsetDateTime createdAt
) {
    public SafeTaskEventResponse {
        payload = Objects.requireNonNull(payload, "payload must not be null").deepCopy();
    }

    @Override
    public JsonNode payload() {
        return payload.deepCopy();
    }
}
