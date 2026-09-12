package com.agentflow.agent.settings;

import java.util.Map;
import java.util.Objects;

/** Effective per-Agent settings, ready to freeze into a new task's execution snapshot. */
public record ResolvedAgentExecutionSettings(
        String policyVersion,
        int decisionMaxOutputTokens,
        int finalMaxOutputTokens,
        String decisionResponseFormat,
        String thinkingMode,
        int modelCallTimeoutSeconds,
        Map<String, String> sources
) {
    public ResolvedAgentExecutionSettings {
        Objects.requireNonNull(policyVersion, "policyVersion must not be null");
        Objects.requireNonNull(decisionResponseFormat, "decisionResponseFormat must not be null");
        Objects.requireNonNull(thinkingMode, "thinkingMode must not be null");
        sources = Map.copyOf(sources);
    }
}
