package com.agentflow.infra.llm;

/**
 * Token counts from the provider. All three values are {@code null} when the provider did
 * not return usage; zero is therefore preserved as a real provider-reported value.
 */
public record LlmTokenUsage(Integer inputTokens, Integer outputTokens, Integer totalTokens) {
    public LlmTokenUsage {
        boolean allUnknown = inputTokens == null && outputTokens == null && totalTokens == null;
        boolean allKnown = inputTokens != null && outputTokens != null && totalTokens != null;
        if (!allUnknown && !allKnown) {
            throw new IllegalArgumentException("token usage must be either fully known or fully unknown");
        }
        if (allKnown && (inputTokens < 0 || outputTokens < 0 || totalTokens < 0)) {
            throw new IllegalArgumentException("token usage must not be negative");
        }
    }

    public static LlmTokenUsage unknown() {
        return new LlmTokenUsage(null, null, null);
    }

    public static LlmTokenUsage known(int inputTokens, int outputTokens, int totalTokens) {
        return new LlmTokenUsage(inputTokens, outputTokens, totalTokens);
    }

    public boolean known() {
        return inputTokens != null;
    }
}
