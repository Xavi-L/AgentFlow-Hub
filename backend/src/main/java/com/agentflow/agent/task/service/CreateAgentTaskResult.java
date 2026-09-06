package com.agentflow.agent.task.service;

import com.agentflow.agent.task.model.AgentTask;
import java.util.Objects;

/** Internal creation outcome, including recovery after a concurrent unique-key conflict. */
public record CreateAgentTaskResult(AgentTask task, boolean created) {
    public CreateAgentTaskResult {
        Objects.requireNonNull(task, "task must not be null");
    }
}
