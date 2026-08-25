package com.agentflow.knowledge.chat;

/** External boundary for one non-streaming answer grounded in a V8 context. */
public interface ChatGateway {
    String generate(ChatRequest request);
}
