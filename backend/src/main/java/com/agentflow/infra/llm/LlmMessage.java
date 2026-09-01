package com.agentflow.infra.llm;

/** One ordered text message. Validation occurs at the gateway before any provider I/O. */
public record LlmMessage(LlmMessageRole role, String content) {
}
