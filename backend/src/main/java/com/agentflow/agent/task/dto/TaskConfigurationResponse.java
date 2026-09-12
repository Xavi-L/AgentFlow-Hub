package com.agentflow.agent.task.dto;

import com.agentflow.agent.task.model.AgentTask;

/** Historical absence remains unknown; never query today's Agent to fill it. */
public record TaskConfigurationResponse(String configVersionId, String configHash,
        String effectiveConfigHash, String hashAlgorithmVersion) {
    public static TaskConfigurationResponse from(AgentTask task) {
        if (task.getConfigVersionId() == null) return null;
        return new TaskConfigurationResponse(task.getConfigVersionId().toString(), task.getConfigHash(),
                task.getEffectiveConfigHash(), task.getHashAlgorithmVersion());
    }
}
