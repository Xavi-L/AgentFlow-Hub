package com.agentflow.agent.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentflow.agent.engine.TaskExecutionProperties;
import com.agentflow.agent.model.AgentApp;
import com.agentflow.common.error.BusinessException;
import com.agentflow.config.OpenAiChatProperties;
import java.time.Duration;
import java.util.HashMap;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentExecutionSettingsPolicyTest {
    private final TaskExecutionProperties defaults = new TaskExecutionProperties();
    private final OpenAiChatProperties chat = new OpenAiChatProperties();
    private final AgentExecutionPolicyProperties limits = new AgentExecutionPolicyProperties();
    private final AgentExecutionSettingsPolicy policy = new AgentExecutionSettingsPolicy(defaults, chat, limits);

    @Test
    void shouldInheritDefaultsAndFreezeTheComputedFinalReserveWithoutMutatingTheAgent() {
        chat.setTimeout(Duration.ofMillis(30001));
        AgentApp agent = agent();
        var resolved = policy.resolve(agent);
        assertThat(resolved.decisionMaxOutputTokens()).isEqualTo(512);
        assertThat(resolved.finalMaxOutputTokens()).isEqualTo(2000);
        assertThat(resolved.modelCallTimeoutSeconds()).isEqualTo(31);
        assertThat(resolved.sources()).containsEntry("decisionMaxOutputTokens", "DEPLOYMENT_DEFAULT")
                .containsEntry("finalMaxOutputTokens", "FINAL_RESERVE");
        assertThat(agent.getFinalMaxOutputTokens()).isNull();
        assertThat(policy.executionOptions("local-model").defaults().finalMaxOutputTokens()).isNull();
    }

    @Test
    void shouldFloorOnlyInheritedFinalDefaultsAndRejectAnExplicitOverrideBelowTheReserve() {
        defaults.setFinalMaxOutputTokens(512);
        AgentApp agent = agent();
        assertThat(policy.resolve(agent).finalMaxOutputTokens()).isEqualTo(2000);
        agent.setFinalMaxOutputTokens(1999);
        assertThatThrownBy(() -> policy.resolve(agent)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("at least the final reserve of 2000");
        agent.setFinalMaxOutputTokens(2000);
        assertThat(policy.resolve(agent).sources()).containsEntry("finalMaxOutputTokens", "AGENT_OVERRIDE");
        agent.setMaxTokens(12000);
        assertThatThrownBy(() -> policy.resolve(agent)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("2048");
        agent.setFinalMaxOutputTokens(null);
        assertThat(policy.resolve(agent).finalMaxOutputTokens()).isEqualTo(2048);
    }

    @Test
    void shouldRequireExactVerifiedModelCapabilitiesEvenForInheritedDeploymentModes() {
        defaults.setDecisionJsonSchemaEnabled(true);
        defaults.setProviderThinkingDisabled(true);
        limits.setJsonSchemaModels(Set.of("verified"));
        limits.setThinkingDisabledModels(Set.of("verified"));
        AgentApp agent = agent();
        var inherited = policy.resolve(agent);
        assertThat(inherited.decisionResponseFormat()).isEqualTo("PROMPT_ONLY");
        assertThat(inherited.thinkingMode()).isEqualTo("PROVIDER_DEFAULT");
        assertThat(inherited.sources()).containsEntry("decisionResponseFormat", "DEPLOYMENT_DEFAULT_FALLBACK")
                .containsEntry("thinkingMode", "DEPLOYMENT_DEFAULT_FALLBACK");
        assertThat(policy.executionOptions("verified").defaults().decisionResponseFormat()).isEqualTo("JSON_SCHEMA");
        assertThat(policy.executionOptions("Verified").capabilities().jsonSchema()).isFalse();
        agent.setDecisionResponseFormat("JSON_SCHEMA");
        assertThatThrownBy(() -> policy.resolve(agent)).hasMessageContaining("not verified");
        agent.setModelName("verified");
        agent.setThinkingMode("DISABLED");
        assertThat(policy.resolve(agent).thinkingMode()).isEqualTo("DISABLED");
        agent.setDecisionResponseFormat("JSON_OBJECT");
        assertThatThrownBy(() -> policy.resolve(agent)).hasMessageContaining("not verified");
        limits.setJsonObjectModels(Set.of("verified"));
        assertThat(policy.resolve(agent).decisionResponseFormat()).isEqualTo("JSON_OBJECT");
        agent.setDecisionResponseFormat("json_object");
        assertThatThrownBy(() -> policy.resolve(agent)).hasMessageContaining("unsupported");
        agent.setDecisionResponseFormat("PROMPT_ONLY");
        agent.setThinkingMode("ENABLED");
        assertThatThrownBy(() -> policy.resolve(agent)).hasMessageContaining("unsupported");
    }

    @Test
    void shouldRejectOverridesBeyondDeploymentCeilingsRatherThanSilentlyClampThem() {
        limits.setMaxOutputTokens(4096);
        limits.setMaxModelCallTimeoutSeconds(120);
        AgentApp agent = agent();
        agent.setDecisionMaxOutputTokens(4096);
        agent.setFinalMaxOutputTokens(4096);
        agent.setModelCallTimeoutSeconds(120);
        assertThat(policy.resolve(agent).decisionMaxOutputTokens()).isEqualTo(4096);
        agent.setDecisionMaxOutputTokens(4097);
        assertThatThrownBy(() -> policy.resolve(agent)).hasMessageContaining("decisionMaxOutputTokens");
        agent.setDecisionMaxOutputTokens(0);
        assertThatThrownBy(() -> policy.resolve(agent)).hasMessageContaining("between 1 and 4096");
        agent.setDecisionMaxOutputTokens(null);
        agent.setFinalMaxOutputTokens(4097);
        assertThatThrownBy(() -> policy.resolve(agent)).hasMessageContaining("finalMaxOutputTokens");
        agent.setFinalMaxOutputTokens(null);
        agent.setModelCallTimeoutSeconds(121);
        assertThatThrownBy(() -> policy.resolve(agent)).hasMessageContaining("modelCallTimeoutSeconds");
        agent.setModelCallTimeoutSeconds(0);
        assertThatThrownBy(() -> policy.resolve(agent)).hasMessageContaining("modelCallTimeoutSeconds");
    }

    @Test
    void shouldRejectInvalidDeploymentDefaultsAndProtectResolvedSourcesFromMutation() {
        limits.setMaxOutputTokens(256);
        assertThatThrownBy(() -> policy.executionOptions("local-model"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("final reserve of 2048");
        limits.setMaxOutputTokens(2048);
        defaults.setDecisionMaxOutputTokens(2049);
        assertThatThrownBy(() -> policy.executionOptions("local-model"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("decisionMaxOutputTokens default");
        defaults.setDecisionMaxOutputTokens(512);
        limits.setMaxOutputTokens(16384);
        defaults.setDecisionJsonObjectEnabled(true);
        defaults.setDecisionJsonSchemaEnabled(true);
        assertThatThrownBy(() -> policy.resolve(agent())).hasMessageContaining("mutually exclusive");
        defaults.setDecisionJsonObjectEnabled(false);
        var sources = new HashMap<String, String>();
        sources.put("decisionMaxOutputTokens", "AGENT_OVERRIDE");
        var value = new ResolvedAgentExecutionSettings("v1", 512, 2000, "PROMPT_ONLY", "PROVIDER_DEFAULT", 30, sources);
        sources.clear();
        assertThat(value.sources()).hasSize(1);
        assertThatThrownBy(() -> value.sources().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    private static AgentApp agent() {
        AgentApp agent = new AgentApp();
        agent.setModelName("local-model");
        agent.setMaxTokens(8000);
        return agent;
    }
}
