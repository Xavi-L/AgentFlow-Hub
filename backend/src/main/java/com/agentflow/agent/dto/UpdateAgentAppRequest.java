package com.agentflow.agent.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.math.BigDecimal;
import java.util.Set;

/**
 * 中文：V32 当前 owner 可部分更新的 Agent 公开配置。{@code presentFields} 只用于区分
 * JSON 字段缺失与显式 null，不是客户端可命名的 JSON 字段。
 *
 * <p>English: V32 partial update input for the current owner's public Agent configuration.
 * {@code presentFields} distinguishes an absent JSON field from an explicit null and is not
 * itself client-addressable JSON input.
 */
@JsonDeserialize(using = UpdateAgentAppRequestDeserializer.class)
public record UpdateAgentAppRequest(
        Set<String> presentFields,
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
        Integer decisionMaxOutputTokens,
        Integer finalMaxOutputTokens,
        String decisionResponseFormat,
        String thinkingMode,
        Integer modelCallTimeoutSeconds
) {
    /** Source compatibility for callers that inherit all advanced settings. */
    public UpdateAgentAppRequest(Set<String> presentFields, String name, String description, String systemPrompt, String modelProvider, String modelName,
            BigDecimal temperature, BigDecimal topP, Integer maxSteps, Integer maxToolCalls,
            Integer maxTokens, Integer timeoutSeconds) {
        this(presentFields, name, description, systemPrompt, modelProvider, modelName, temperature, topP,
                maxSteps, maxToolCalls, maxTokens, timeoutSeconds, null, null, null, null, null);
    }

    static final Set<String> CONFIG_FIELDS = Set.of(
            "name",
            "description",
            "systemPrompt",
            "modelProvider",
            "modelName",
            "temperature",
            "topP",
            "maxSteps",
            "maxToolCalls",
            "maxTokens",
            "timeoutSeconds",
            "decisionMaxOutputTokens",
            "finalMaxOutputTokens",
            "decisionResponseFormat",
            "thinkingMode",
            "modelCallTimeoutSeconds"
    );

    public UpdateAgentAppRequest {
        presentFields = presentFields == null ? Set.of() : Set.copyOf(presentFields);
        if (!CONFIG_FIELDS.containsAll(presentFields)) {
            throw new IllegalArgumentException("presentFields contains an unsupported Agent field");
        }
    }

    public boolean hasAnyConfigField() {
        return !presentFields.isEmpty();
    }

    public boolean namePresent() {
        return presentFields.contains("name");
    }

    public boolean descriptionPresent() {
        return presentFields.contains("description");
    }

    public boolean systemPromptPresent() {
        return presentFields.contains("systemPrompt");
    }

    public boolean modelProviderPresent() {
        return presentFields.contains("modelProvider");
    }

    public boolean modelNamePresent() {
        return presentFields.contains("modelName");
    }

    public boolean temperaturePresent() {
        return presentFields.contains("temperature");
    }

    public boolean topPPresent() {
        return presentFields.contains("topP");
    }

    public boolean maxStepsPresent() {
        return presentFields.contains("maxSteps");
    }

    public boolean maxToolCallsPresent() {
        return presentFields.contains("maxToolCalls");
    }

    public boolean maxTokensPresent() {
        return presentFields.contains("maxTokens");
    }

    public boolean timeoutSecondsPresent() {
        return presentFields.contains("timeoutSeconds");
    }

    public boolean decisionMaxOutputTokensPresent() { return presentFields.contains("decisionMaxOutputTokens"); }
    public boolean finalMaxOutputTokensPresent() { return presentFields.contains("finalMaxOutputTokens"); }
    public boolean decisionResponseFormatPresent() { return presentFields.contains("decisionResponseFormat"); }
    public boolean thinkingModePresent() { return presentFields.contains("thinkingMode"); }
    public boolean modelCallTimeoutSecondsPresent() { return presentFields.contains("modelCallTimeoutSeconds"); }
}
