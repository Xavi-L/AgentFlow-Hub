package com.agentflow.agent.task.service;

/** Internal M4C command. HTTP Idempotency-Key mapping belongs to M4F. */
public record CreateAgentTaskCommand(
        long userId,
        long agentId,
        String clientRequestId,
        String userInput
) {
}
