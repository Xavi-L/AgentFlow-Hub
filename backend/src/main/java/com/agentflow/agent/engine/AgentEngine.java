package com.agentflow.agent.engine;

/** Synchronous, in-process boundary for one non-persistent Agent execution. */
public interface AgentEngine {
    AgentExecutionResult execute(AgentExecutionCommand command);

    com.agentflow.agent.task.execution.TaskExecutionOutcome execute(
            com.agentflow.agent.task.execution.TaskExecutionRequest request
    );
}
