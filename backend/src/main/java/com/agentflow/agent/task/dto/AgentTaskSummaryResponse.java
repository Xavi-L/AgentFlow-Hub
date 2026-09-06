package com.agentflow.agent.task.dto;

import com.agentflow.agent.task.model.AgentTask;
import java.time.OffsetDateTime;

/** Compact historical list entry, with no dependency on live Agent/resource tables. */
public record AgentTaskSummaryResponse(
        String taskId, String agentId, String status, String phase, String terminationReason,
        String userInput, OffsetDateTime createdAt, OffsetDateTime updatedAt, OffsetDateTime completedAt
) {
    public static AgentTaskSummaryResponse from(AgentTask task) {
        return new AgentTaskSummaryResponse(
                task.getId().toString(), task.getAgentId().toString(), task.getStatus(), task.getPhase(),
                task.getTerminationReason(), task.getUserInput(), task.getCreatedAt(), task.getUpdatedAt(),
                task.getCompletedAt()
        );
    }
}
