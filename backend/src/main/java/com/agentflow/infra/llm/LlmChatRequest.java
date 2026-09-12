package com.agentflow.infra.llm;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Provider-neutral input for one chat completion. {@code maxOutputTokens} is a per-call
 * output cap; it is deliberately independent from an Agent task's total token budget.
 * Optional response/thinking modes are sent only when explicitly requested and verified
 * against the selected provider; absent modes retain the legacy request shape.
 */
public record LlmChatRequest(
        String modelProvider,
        String modelName,
        List<LlmMessage> messages,
        BigDecimal temperature,
        BigDecimal topP,
        int maxOutputTokens,
        @JsonInclude(JsonInclude.Include.NON_NULL) LlmResponseSchema responseSchema,
        @JsonInclude(JsonInclude.Include.NON_NULL) String responseFormat,
        @JsonInclude(JsonInclude.Include.NON_NULL) String thinkingMode,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer timeoutSeconds
) {
    public LlmChatRequest(String modelProvider, String modelName, List<LlmMessage> messages,
            BigDecimal temperature, BigDecimal topP, int maxOutputTokens) {
        this(modelProvider, modelName, messages, temperature, topP, maxOutputTokens, null, null, null);
    }

    public LlmChatRequest(String modelProvider, String modelName, List<LlmMessage> messages,
            BigDecimal temperature, BigDecimal topP, int maxOutputTokens, LlmResponseSchema responseSchema) {
        this(modelProvider, modelName, messages, temperature, topP, maxOutputTokens, responseSchema, null, null);
    }

    public LlmChatRequest(String modelProvider, String modelName, List<LlmMessage> messages,
            BigDecimal temperature, BigDecimal topP, int maxOutputTokens, LlmResponseSchema responseSchema,
            String responseFormat, String thinkingMode) {
        this(modelProvider, modelName, messages, temperature, topP, maxOutputTokens,
                responseSchema, responseFormat, thinkingMode, null);
    }

    public LlmChatRequest {
        if (responseFormat != null && !"json_object".equals(responseFormat)) {
            throw new IllegalArgumentException("responseFormat must be json_object when provided");
        }
        if (responseFormat != null && responseSchema != null) {
            throw new IllegalArgumentException("responseFormat and responseSchema are mutually exclusive");
        }
        if (thinkingMode != null && !"disabled".equals(thinkingMode)) {
            throw new IllegalArgumentException("thinkingMode must be disabled when provided");
        }
        if (timeoutSeconds != null && (timeoutSeconds < 1 || timeoutSeconds > 600)) {
            throw new IllegalArgumentException("timeoutSeconds must be between 1 and 600 when provided");
        }
        messages = messages == null
                ? null
                : Collections.unmodifiableList(new ArrayList<>(messages));
    }
}
