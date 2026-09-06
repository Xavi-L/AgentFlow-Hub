package com.agentflow.agent.task.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.Objects;

/** Safe event projection shared by Trace and SSE replay. Sequence numbers remain numeric. */
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
