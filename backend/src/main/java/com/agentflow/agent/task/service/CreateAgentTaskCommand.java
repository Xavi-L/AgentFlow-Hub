package com.agentflow.agent.task.service;

/** Internal creation command; V41 maps the authenticated owner and HTTP Idempotency-Key. */
public record CreateAgentTaskCommand(
        long userId,
        long agentId,
        String clientRequestId,
        String userInput
) {
}
