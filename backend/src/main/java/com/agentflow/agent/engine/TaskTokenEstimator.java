package com.agentflow.agent.engine;

import com.agentflow.infra.llm.LlmMessage;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Conservative provider-neutral estimate: one token per UTF-8 byte plus framing overhead. */
public final class TaskTokenEstimator {
    private TaskTokenEstimator() { }

    public static int inputTokens(List<LlmMessage> messages) {
        long total = 16;
        for (LlmMessage message : messages) {
            total += 8L + textTokens(message.content());
        }
        return Math.toIntExact(total);
    }

    public static int textTokens(String text) {
        return Math.max(1, text.getBytes(StandardCharsets.UTF_8).length);
    }
}
