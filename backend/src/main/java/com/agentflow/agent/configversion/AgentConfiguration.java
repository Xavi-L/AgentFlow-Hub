package com.agentflow.agent.configversion;

import com.agentflow.agent.binding.model.AgentKnowledgeBinding;
import com.agentflow.agent.binding.model.AgentToolBinding;
import com.agentflow.agent.model.AgentApp;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Complete raw configuration choices. Null overrides mean inheritance, never today's defaults. */
public record AgentConfiguration(String systemPrompt, Model model, Budgets budgets,
        Overrides executionSettingsOverrides, List<KnowledgeBinding> knowledgeBindings,
        List<ToolBinding> toolBindings) {
    public static final String SCHEMA_VERSION = "agent-config-v1";

    public AgentConfiguration {
        Objects.requireNonNull(systemPrompt);
        Objects.requireNonNull(model);
        Objects.requireNonNull(budgets);
        Objects.requireNonNull(executionSettingsOverrides);
        // Binding rows are a set, but priority is an execution choice and remains in the content.
        knowledgeBindings = knowledgeBindings.stream()
                .sorted(Comparator.comparingLong(b -> Long.parseLong(b.knowledgeBaseId()))).toList();
        toolBindings = toolBindings.stream()
                .sorted(Comparator.comparingLong(b -> Long.parseLong(b.toolId()))).toList();
    }

    public static AgentConfiguration capture(AgentApp agent, List<AgentKnowledgeBinding> knowledge,
            List<AgentToolBinding> tools) {
        return new AgentConfiguration(agent.getSystemPrompt(),
                new Model(agent.getModelProvider(), agent.getModelName(), agent.getTemperature(), agent.getTopP()),
                new Budgets(agent.getMaxSteps(), agent.getMaxToolCalls(), agent.getMaxTokens(), agent.getTimeoutSeconds()),
                new Overrides(agent.getDecisionMaxOutputTokens(), agent.getFinalMaxOutputTokens(),
                        agent.getDecisionResponseFormat(), agent.getThinkingMode(), agent.getModelCallTimeoutSeconds()),
                knowledge.stream().map(b -> new KnowledgeBinding(b.getKnowledgeBaseId().toString(), b.getPriority())).toList(),
                tools.stream().map(b -> new ToolBinding(b.getToolId().toString(), b.getEnabled(), b.getPriority())).toList());
    }

    /** Only the live ACTIVE status and identity come from the current Agent; all choices are frozen. */
    public AgentApp toExecutionAgent(long agentId, String currentStatus) {
        AgentApp result = new AgentApp();
        result.setId(agentId);
        result.setStatus(currentStatus);
        result.setSystemPrompt(systemPrompt);
        result.setModelProvider(model.provider());
        result.setModelName(model.name());
        result.setTemperature(model.temperature());
        result.setTopP(model.topP());
        result.setMaxSteps(budgets.maxDecisionTurns());
        result.setMaxToolCalls(budgets.maxToolCalls());
        result.setMaxTokens(budgets.maxTotalTokens());
        result.setTimeoutSeconds(budgets.timeoutSeconds());
        result.setDecisionMaxOutputTokens(executionSettingsOverrides.decisionMaxOutputTokens());
        result.setFinalMaxOutputTokens(executionSettingsOverrides.finalMaxOutputTokens());
        result.setDecisionResponseFormat(executionSettingsOverrides.decisionResponseFormat());
        result.setThinkingMode(executionSettingsOverrides.thinkingMode());
        result.setModelCallTimeoutSeconds(executionSettingsOverrides.modelCallTimeoutSeconds());
        return result;
    }

    public List<Long> orderedKnowledgeIds() {
        return knowledgeBindings.stream().sorted(Comparator.comparingInt(KnowledgeBinding::priority)
                .thenComparingLong(b -> Long.parseLong(b.knowledgeBaseId())))
                .map(b -> Long.parseLong(b.knowledgeBaseId())).toList();
    }

    public List<Long> orderedEnabledToolIds() {
        return toolBindings.stream().filter(ToolBinding::enabled)
                .sorted(Comparator.comparingInt(ToolBinding::priority).thenComparingLong(b -> Long.parseLong(b.toolId())))
                .map(b -> Long.parseLong(b.toolId())).toList();
    }

    public record Model(String provider, String name, BigDecimal temperature, BigDecimal topP) { }
    public record Budgets(int maxDecisionTurns, int maxToolCalls, int maxTotalTokens, int timeoutSeconds) { }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Overrides(Integer decisionMaxOutputTokens, Integer finalMaxOutputTokens,
            String decisionResponseFormat, String thinkingMode, Integer modelCallTimeoutSeconds) { }
    public record KnowledgeBinding(String knowledgeBaseId, int priority) { }
    public record ToolBinding(String toolId, boolean enabled, int priority) { }
}
