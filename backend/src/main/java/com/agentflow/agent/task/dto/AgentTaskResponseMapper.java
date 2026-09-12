package com.agentflow.agent.task.dto;

import com.agentflow.agent.task.model.AgentTask;
import org.springframework.stereotype.Component;

/** Explicit projection excludes owner, idempotency fingerprint, lock version and raw snapshot. */
@Component
public class AgentTaskResponseMapper {
    private final SafeTaskPayloadProjector payloads;

    public AgentTaskResponseMapper(SafeTaskPayloadProjector payloads) {
        this.payloads = payloads;
    }

    public AgentTaskResponse toResponse(AgentTask task) {
        return new AgentTaskResponse(
                task.getId().toString(), task.getAgentId().toString(), task.getStatus(), task.getPhase(),
                task.getTerminationReason(), task.getUserInput(), task.getMaxDecisionTurns(),
                task.getMaxToolCalls(), task.getMaxTotalTokens(), task.getReservedFinalTokens(),
                task.getDecisionTurnsUsed(), task.getToolCallsUsed(), task.getInputTokens(),
                task.getOutputTokens(), task.getTotalTokens(), task.getTokenUsageQuality(),
                task.getFinalAnswer(), payloads.parse(task.getCitations()), task.getErrorCode(),
                payloads.projectErrorMessage(task.getErrorMessage()), task.getCancelRequestedAt(),
                task.getStartedAt(), task.getCompletedAt(), task.getLastEventSequence(),
                task.getCreatedAt(), task.getUpdatedAt(), payloads.projectRecovery(task.getRecoveryMetadata()),
                TaskConfigurationResponse.from(task)
        );
    }
}
