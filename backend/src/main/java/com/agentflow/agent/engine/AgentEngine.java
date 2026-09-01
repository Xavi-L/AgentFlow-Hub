package com.agentflow.agent.engine;

/** Synchronous, in-process boundary for one non-persistent Agent execution. */
public interface AgentEngine {
    AgentExecutionResult execute(AgentExecutionCommand command);
}
