package com.agentflow.agent.engine;

import com.agentflow.agent.model.AgentApp;
import java.math.BigDecimal;
import java.util.Objects;

/** Immutable copy of every Agent field that may influence one V36 execution. */
public record AgentExecutionConfigSnapshot(
        String systemPrompt,
        String modelProvider,
        String modelName,
        BigDecimal temperature,
        BigDecimal topP,
        int maxSteps,
        int maxToolCalls,
        int maxTokens,
        int timeoutSeconds,
        String status
) {
    private static final String ACTIVE = "ACTIVE";
    private static final String DISABLED = "DISABLED";
    private static final String SUPPORTED_PROVIDER = "openai-compatible";

    public AgentExecutionConfigSnapshot {
        requireText(systemPrompt);
        if (systemPrompt.length() > 20_000) {
            throw invalid();
        }
        if (!SUPPORTED_PROVIDER.equals(modelProvider)) {
            throw invalid();
        }
        requireText(modelName);
        Objects.requireNonNull(temperature, "temperature must not be null");
        Objects.requireNonNull(topP, "topP must not be null");
        if (temperature.compareTo(BigDecimal.ZERO) < 0
                || temperature.compareTo(new BigDecimal("2")) > 0
                || topP.compareTo(BigDecimal.ZERO) <= 0
                || topP.compareTo(BigDecimal.ONE) > 0
                || maxSteps < 1 || maxSteps > 20
                || maxToolCalls < 0 || maxToolCalls > 20
                || maxToolCalls >= maxSteps
                || maxTokens < 256 || maxTokens > 100_000
                || timeoutSeconds < 1 || timeoutSeconds > 600
                || (!ACTIVE.equals(status) && !DISABLED.equals(status))) {
            throw invalid();
        }
    }

    public static AgentExecutionConfigSnapshot from(AgentApp agent) {
        Objects.requireNonNull(agent, "agent must not be null");
        return new AgentExecutionConfigSnapshot(
                agent.getSystemPrompt(),
                agent.getModelProvider(),
                agent.getModelName(),
                agent.getTemperature(),
                agent.getTopP(),
                agent.getMaxSteps(),
                agent.getMaxToolCalls(),
                agent.getMaxTokens(),
                agent.getTimeoutSeconds(),
                agent.getStatus()
        );
    }

    public boolean active() {
        return ACTIVE.equals(status);
    }

    private static void requireText(String value) {
        if (value == null || value.isBlank()) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid persisted Agent execution configuration");
    }
}
