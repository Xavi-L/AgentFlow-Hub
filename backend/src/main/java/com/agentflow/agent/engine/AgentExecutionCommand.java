package com.agentflow.agent.engine;

/** Owner-scoped input for the V36 internal execution boundary. */
public record AgentExecutionCommand(
        Long userId,
        Long agentId,
        String userInput
) {
}
