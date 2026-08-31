package com.agentflow.agent.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 中文：V30 创建 Agent 的唯一客户端输入。局部反序列化器采用字段 allowlist；owner、状态、
 * config、绑定关系和审计字段不属于该请求。
 *
 * <p>English: The only client input for V30 Agent creation. Its local deserializer uses a
 * field allowlist; ownership, status, config, bindings, and audit fields are not request data.
 */
@JsonDeserialize(using = CreateAgentAppRequestDeserializer.class)
public record CreateAgentAppRequest(
        @NotBlank(message = "name must not be blank")
        @Size(max = 128, message = "name must not exceed 128 characters")
        String name,

        @Size(max = 4_000, message = "description must not exceed 4000 characters")
        String description,

        @NotBlank(message = "systemPrompt must not be blank")
        @Size(max = 20_000, message = "systemPrompt must not exceed 20000 characters")
        String systemPrompt,

        @NotBlank(message = "modelProvider must not be blank")
        @Size(max = 64, message = "modelProvider must not exceed 64 characters")
        @Pattern(
                regexp = "openai-compatible",
                message = "modelProvider must be openai-compatible"
        )
        String modelProvider,

        @NotBlank(message = "modelName must not be blank")
        @Size(max = 128, message = "modelName must not exceed 128 characters")
        String modelName,

        @DecimalMin(value = "0", message = "temperature must not be less than 0")
        @DecimalMax(value = "2", message = "temperature must not exceed 2")
        @Digits(integer = 1, fraction = 3, message = "temperature must have at most 3 decimal places")
        BigDecimal temperature,

        @DecimalMin(value = "0", inclusive = false, message = "topP must be greater than 0")
        @DecimalMax(value = "1", message = "topP must not exceed 1")
        @Digits(integer = 1, fraction = 3, message = "topP must have at most 3 decimal places")
        BigDecimal topP,

        @Min(value = 1, message = "maxSteps must be at least 1")
        @Max(value = 20, message = "maxSteps must not exceed 20")
        Integer maxSteps,

        @Min(value = 0, message = "maxToolCalls must not be negative")
        @Max(value = 20, message = "maxToolCalls must not exceed 20")
        Integer maxToolCalls,

        @Min(value = 256, message = "maxTokens must be at least 256")
        @Max(value = 100_000, message = "maxTokens must not exceed 100000")
        Integer maxTokens,

        @Min(value = 1, message = "timeoutSeconds must be at least 1")
        @Max(value = 600, message = "timeoutSeconds must not exceed 600")
        Integer timeoutSeconds
) {
    public static final BigDecimal DEFAULT_TEMPERATURE = new BigDecimal("0.2");
    public static final BigDecimal DEFAULT_TOP_P = new BigDecimal("0.8");
    public static final int DEFAULT_MAX_STEPS = 6;
    public static final int DEFAULT_MAX_TOOL_CALLS = 4;
    public static final int DEFAULT_MAX_TOKENS = 8_000;
    public static final int DEFAULT_TIMEOUT_SECONDS = 120;

    /** Validates the relationship after applying the same V30 defaults used by the service. */
    @AssertTrue(message = "maxToolCalls must not exceed maxSteps")
    @JsonIgnore
    public boolean isToolCallBudgetValid() {
        int effectiveMaxSteps = maxSteps == null ? DEFAULT_MAX_STEPS : maxSteps;
        int effectiveMaxToolCalls = maxToolCalls == null ? DEFAULT_MAX_TOOL_CALLS : maxToolCalls;
        return effectiveMaxToolCalls <= effectiveMaxSteps;
    }
}
