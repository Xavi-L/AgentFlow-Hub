package com.agentflow.agent.dto;

import com.agentflow.agent.model.AgentApp;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Full public V30 creation response; persistence-only owner/config/deletion fields stay internal. */
public record AgentAppResponse(
        String id,
        String name,
        String description,
        String systemPrompt,
        String modelProvider,
        String modelName,
        BigDecimal temperature,
        BigDecimal topP,
        Integer maxSteps,
        Integer maxToolCalls,
        Integer maxTokens,
        Integer timeoutSeconds,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static AgentAppResponse from(AgentApp agentApp) {
        return new AgentAppResponse(
                String.valueOf(agentApp.getId()),
                agentApp.getName(),
                agentApp.getDescription(),
                agentApp.getSystemPrompt(),
                agentApp.getModelProvider(),
                agentApp.getModelName(),
                normalizeDecimal(agentApp.getTemperature()),
                normalizeDecimal(agentApp.getTopP()),
                agentApp.getMaxSteps(),
                agentApp.getMaxToolCalls(),
                agentApp.getMaxTokens(),
                agentApp.getTimeoutSeconds(),
                agentApp.getStatus(),
                agentApp.getCreatedAt(),
                agentApp.getUpdatedAt()
        );
    }

    private static BigDecimal normalizeDecimal(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros();
    }
}
