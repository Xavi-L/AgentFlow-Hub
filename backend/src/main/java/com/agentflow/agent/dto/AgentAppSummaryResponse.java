package com.agentflow.agent.dto;

import com.agentflow.agent.model.AgentApp;
import java.time.OffsetDateTime;

/**
 * Current-owner list item. The prompt and execution-budget fields are intentionally omitted
 * so page rows remain a compact metadata summary.
 */
public record AgentAppSummaryResponse(
        String id,
        String name,
        String description,
        String modelProvider,
        String modelName,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static AgentAppSummaryResponse from(AgentApp agentApp) {
        return new AgentAppSummaryResponse(
                String.valueOf(agentApp.getId()),
                agentApp.getName(),
                agentApp.getDescription(),
                agentApp.getModelProvider(),
                agentApp.getModelName(),
                agentApp.getStatus(),
                agentApp.getCreatedAt(),
                agentApp.getUpdatedAt()
        );
    }
}
