package com.agentflow.agent.engine;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** Deployment ceiling; each call also obeys the frozen task budget and context limit. */
@Component
@Validated
@ConfigurationProperties("agentflow.task.execution")
public class TaskExecutionProperties {
    @Min(1)
    @Max(64)
    private int maxConcurrentExternalCalls = 4;

    public int getMaxConcurrentExternalCalls() { return maxConcurrentExternalCalls; }

    public void setMaxConcurrentExternalCalls(int maxConcurrentExternalCalls) {
        this.maxConcurrentExternalCalls = maxConcurrentExternalCalls;
    }

    @Min(1)
    @Max(16384)
    private int decisionMaxOutputTokens = 512;
    private boolean decisionJsonSchemaEnabled;
    private boolean decisionJsonObjectEnabled;
    private boolean providerThinkingDisabled;
    @Min(1)
    @Max(16384)
    private Integer finalMaxOutputTokens;

    @AssertTrue(message = "decision JSON schema and JSON object modes are mutually exclusive")
    public boolean isDecisionFormatExclusive() {
        return !decisionJsonSchemaEnabled || !decisionJsonObjectEnabled;
    }

    public boolean isDecisionJsonObjectEnabled() {
        return decisionJsonObjectEnabled;
    }

    public void setDecisionJsonObjectEnabled(boolean decisionJsonObjectEnabled) {
        this.decisionJsonObjectEnabled = decisionJsonObjectEnabled;
    }

    public boolean isProviderThinkingDisabled() {
        return providerThinkingDisabled;
    }

    public void setProviderThinkingDisabled(boolean providerThinkingDisabled) {
        this.providerThinkingDisabled = providerThinkingDisabled;
    }

    public Integer getFinalMaxOutputTokens() {
        return finalMaxOutputTokens;
    }

    public void setFinalMaxOutputTokens(Integer finalMaxOutputTokens) {
        this.finalMaxOutputTokens = finalMaxOutputTokens;
    }

    public boolean isDecisionJsonSchemaEnabled() {
        return decisionJsonSchemaEnabled;
    }

    public void setDecisionJsonSchemaEnabled(boolean decisionJsonSchemaEnabled) {
        this.decisionJsonSchemaEnabled = decisionJsonSchemaEnabled;
    }

    public int getDecisionMaxOutputTokens() {
        return decisionMaxOutputTokens;
    }

    public void setDecisionMaxOutputTokens(int decisionMaxOutputTokens) {
        this.decisionMaxOutputTokens = decisionMaxOutputTokens;
    }
}
