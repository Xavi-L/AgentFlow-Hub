package com.agentflow.agent.task.execution;

import com.agentflow.agent.engine.AgentEngine;
import org.springframework.stereotype.Component;

/** Production M4E bridge; no task terminal writes occur inside the engine. */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "agentflow.task.execution", name = "mode", havingValue = "engine", matchIfMissing = true)
public final class AgentEngineTaskExecutionDelegate implements TaskExecutionDelegate {
    private final AgentEngine engine;

    public AgentEngineTaskExecutionDelegate(AgentEngine engine) { this.engine = engine; }

    @Override
    public TaskExecutionOutcome execute(TaskExecutionRequest request) { return engine.execute(request); }
}
