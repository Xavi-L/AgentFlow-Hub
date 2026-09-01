package com.agentflow.infra.llm;

/** Synchronous, provider-neutral boundary for one non-streaming LLM chat completion. */
public interface LlmGateway {
    LlmChatResult chat(LlmChatRequest request);
}
