package com.agentflow.agent.settings;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Public, credential-free configuration defaults and capabilities for one selected model. */
public record AgentExecutionOptionsResponse(
        String policyVersion,
        String modelName,
        Defaults defaults,
        Limits limits,
        Capabilities capabilities
) {
    public record Defaults(
            int decisionMaxOutputTokens,
            @JsonInclude(JsonInclude.Include.ALWAYS) Integer finalMaxOutputTokens,
            String decisionResponseFormat,
            String thinkingMode,
            int modelCallTimeoutSeconds
    ) { }

    public record Limits(int maxOutputTokens, int maxModelCallTimeoutSeconds) { }
    public record Capabilities(boolean jsonObject, boolean jsonSchema, boolean disableThinking) { }
}
