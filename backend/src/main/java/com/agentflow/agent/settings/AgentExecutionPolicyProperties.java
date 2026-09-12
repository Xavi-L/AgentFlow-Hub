package com.agentflow.agent.settings;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** Deployment-owned ceilings and explicitly verified, exact model-name capabilities. */
@Component
@Validated
@ConfigurationProperties("agentflow.agent.execution-policy")
public class AgentExecutionPolicyProperties {
    @Min(2048)
    @Max(16384)
    private int maxOutputTokens = 16384;
    @Min(1)
    @Max(600)
    private int maxModelCallTimeoutSeconds = 600;
    private Set<@NotBlank String> jsonObjectModels = Set.of();
    private Set<@NotBlank String> jsonSchemaModels = Set.of();
    private Set<@NotBlank String> thinkingDisabledModels = Set.of();

    public int getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(int value) { maxOutputTokens = value; }
    public int getMaxModelCallTimeoutSeconds() { return maxModelCallTimeoutSeconds; }
    public void setMaxModelCallTimeoutSeconds(int value) { maxModelCallTimeoutSeconds = value; }
    public Set<String> getJsonObjectModels() { return jsonObjectModels; }
    public void setJsonObjectModels(Set<String> value) { jsonObjectModels = Set.copyOf(value); }
    public Set<String> getJsonSchemaModels() { return jsonSchemaModels; }
    public void setJsonSchemaModels(Set<String> value) { jsonSchemaModels = Set.copyOf(value); }
    public Set<String> getThinkingDisabledModels() { return thinkingDisabledModels; }
    public void setThinkingDisabledModels(Set<String> value) { thinkingDisabledModels = Set.copyOf(value); }
}
