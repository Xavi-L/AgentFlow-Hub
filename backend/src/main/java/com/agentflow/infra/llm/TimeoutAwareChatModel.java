package com.agentflow.infra.llm;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/** A transport that applies an explicit timeout without changing another call's client. */
public interface TimeoutAwareChatModel extends ChatModel {
    ChatResponse call(Prompt prompt, int timeoutSeconds);
}
