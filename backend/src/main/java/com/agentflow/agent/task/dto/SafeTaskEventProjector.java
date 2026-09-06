package com.agentflow.agent.task.dto;

import com.agentflow.agent.task.model.AgentTaskEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/** Never returns the persistence entity or its unprojected JSON payload. */
@Component
public class SafeTaskEventProjector {
    private final SafeTaskPayloadProjector payloadProjector;
    private final ObjectMapper objectMapper;

    public SafeTaskEventProjector(SafeTaskPayloadProjector payloadProjector, ObjectMapper objectMapper) {
        this.payloadProjector = payloadProjector;
        this.objectMapper = objectMapper;
    }

    public SafeTaskEventResponse toResponse(AgentTaskEvent event) {
        JsonNode payload = payloadProjector.parse(event.getPayload());
        // Answer text is the caller-visible result, not runtime metadata. Keep it byte-for-byte
        // consistent with task.finalAnswer, including JSON-looking text split across chunks.
        if ("ANSWER_CHUNK".equals(event.getEventType()) && payload.isObject()) {
            try {
                JsonNode originalText = objectMapper.readTree(event.getPayload()).get("text");
                if (originalText != null && originalText.isTextual()) {
                    ((ObjectNode) payload).set("text", originalText.deepCopy());
                }
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException("Persisted task event is invalid JSON", ex);
            }
        }
        return new SafeTaskEventResponse(
                event.getId().toString(), event.getTaskId().toString(), event.getSequenceNo(),
                event.getEventType(), payload, event.getCreatedAt()
        );
    }
}
