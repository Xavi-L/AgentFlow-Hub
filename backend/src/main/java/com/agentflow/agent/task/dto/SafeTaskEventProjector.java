package com.agentflow.agent.task.dto;

import com.agentflow.agent.task.model.AgentTaskEvent;
import com.agentflow.agent.task.model.TaskEventType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
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
        if (!payload.isObject()) {
            throw new IllegalStateException("Persisted task event payload must be an object");
        }
        TaskEventType eventType;
        try {
            eventType = TaskEventType.valueOf(event.getEventType());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Persisted task event type is not supported", ex);
        }
        // Answer text is the caller-visible result, not runtime metadata. Keep it byte-for-byte
        // consistent with task.finalAnswer, including JSON-looking text split across chunks.
        if (eventType == TaskEventType.ANSWER_CHUNK) {
            try {
                JsonNode originalText = objectMapper.readTree(event.getPayload()).get("text");
                if (originalText != null && originalText.isTextual()) {
                    ((ObjectNode) payload).set("text", originalText.deepCopy());
                }
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException("Persisted task event is invalid JSON", ex);
            }
        }
        // Freeze the public protocol at the producer's business fields. Historical/custom metadata
        // and newly added persistence fields do not become public without an explicit protocol edit.
        ObjectNode publicPayload = objectMapper.createObjectNode();
        for (String field : fields(eventType)) {
            JsonNode value = payload.get(field);
            if (value != null) {
                if (!validFieldType(field, value)) {
                    throw new IllegalStateException("Persisted task event field has an invalid type");
                }
                if ("recovery".equals(field)) {
                    ObjectNode recovery = objectMapper.createObjectNode();
                    for (String key : List.of("schemaVersion", "recoveryRunId", "previousStatus", "reasonCode",
                            "recordCompleteness", "counterCompleteness")) {
                        if (!value.path(key).isTextual()) {
                            throw new IllegalStateException("Persisted recovery event summary has an invalid field");
                        }
                        recovery.set(key, value.get(key).deepCopy());
                    }
                    publicPayload.set(field, recovery);
                } else {
                    publicPayload.set(field, value.deepCopy());
                }
            }
        }
        return new SafeTaskEventResponse(
                event.getId().toString(), event.getTaskId().toString(), event.getSequenceNo(),
                event.getEventType(), publicPayload, event.getCreatedAt()
        );
    }

    private static List<String> fields(TaskEventType type) {
        return switch (type) {
            case TASK_CREATED -> List.of("status");
            case TASK_STARTED -> List.of("status", "phase");
            case PHASE_CHANGED -> List.of("phase");
            case RAG_FINISHED -> List.of("stepId", "validHitCount", "candidateCount", "staleHitCount");
            case DECISION_FINISHED -> List.of("stepId", "totalTokens", "usageQuality", "decisionType");
            case TOOL_STARTED -> List.of("stepId", "toolCode", "reused");
            case TOOL_FINISHED -> List.of("stepId", "toolCode", "reused", "status", "errorCode");
            case FINAL_GENERATION_STARTED -> List.of("maxOutputTokens");
            case ANSWER_CHUNK -> List.of("chunkIndex", "text");
            case TASK_COMPLETED, TASK_TIMED_OUT -> List.of("status", "terminationReason");
            case TASK_CANCELLED -> List.of("status", "terminationReason", "recovery");
            case TASK_FAILED -> List.of("status", "terminationReason", "errorCode", "recovery");
        };
    }

    private static boolean validFieldType(String field, JsonNode value) {
        return switch (field) {
            case "validHitCount", "candidateCount", "staleHitCount", "totalTokens",
                    "maxOutputTokens", "chunkIndex" -> value.isIntegralNumber();
            case "reused" -> value.isBoolean();
            case "recovery" -> value.isObject();
            case "errorCode" -> value.isNull() || value.isTextual();
            default -> value.isTextual();
        };
    }
}
