package com.agentflow.agent.task.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

/** Public persisted task state. Usage counters are committed aggregates, not live telemetry. */
public record AgentTaskResponse(
        String taskId, String agentId, String status, String phase, String terminationReason,
        String userInput, int maxDecisionTurns, int maxToolCalls, int maxTotalTokens,
        int reservedFinalTokens, int decisionTurnsUsed, int toolCallsUsed,
        int inputTokens, int outputTokens, int totalTokens, String tokenUsageQuality,
        String finalAnswer, JsonNode citations, String errorCode, String errorMessage,
        OffsetDateTime cancelRequestedAt, OffsetDateTime startedAt, OffsetDateTime completedAt,
        long lastEventSequence, OffsetDateTime createdAt, OffsetDateTime updatedAt
) {
    public AgentTaskResponse {
        citations = citations.deepCopy();
    }

    @Override
    public JsonNode citations() {
        return citations.deepCopy();
    }
}
