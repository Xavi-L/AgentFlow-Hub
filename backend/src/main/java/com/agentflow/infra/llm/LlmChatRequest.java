package com.agentflow.infra.llm;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Provider-neutral input for one chat completion. {@code maxOutputTokens} is a per-call
 * output cap; it is deliberately independent from an Agent task's total token budget.
 */
public record LlmChatRequest(
        String modelProvider,
        String modelName,
        List<LlmMessage> messages,
        BigDecimal temperature,
        BigDecimal topP,
        int maxOutputTokens
) {
    public LlmChatRequest {
        messages = messages == null
                ? null
                : Collections.unmodifiableList(new ArrayList<>(messages));
    }
}
