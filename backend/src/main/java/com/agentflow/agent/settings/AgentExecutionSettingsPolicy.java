package com.agentflow.agent.settings;

import com.agentflow.agent.engine.TaskExecutionProperties;
import com.agentflow.agent.model.AgentApp;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.config.OpenAiChatProperties;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Resolves nullable overrides without mutating deployment defaults or another Agent. */
@Service
public class AgentExecutionSettingsPolicy {
    public static final String POLICY_VERSION = "agent-execution-policy-v1";
    public static final String PROMPT_ONLY = "PROMPT_ONLY";
    public static final String JSON_OBJECT = "JSON_OBJECT";
    public static final String JSON_SCHEMA = "JSON_SCHEMA";
    public static final String PROVIDER_DEFAULT = "PROVIDER_DEFAULT";
    public static final String DISABLED = "DISABLED";
    private static final Set<String> FORMATS = Set.of(PROMPT_ONLY, JSON_OBJECT, JSON_SCHEMA);
    private static final Set<String> THINKING_MODES = Set.of(PROVIDER_DEFAULT, DISABLED);

    private final TaskExecutionProperties defaults;
    private final OpenAiChatProperties chat;
    private final AgentExecutionPolicyProperties policy;

    public AgentExecutionSettingsPolicy(TaskExecutionProperties defaults, OpenAiChatProperties chat,
            AgentExecutionPolicyProperties policy) {
        this.defaults = Objects.requireNonNull(defaults);
        this.chat = Objects.requireNonNull(chat);
        this.policy = Objects.requireNonNull(policy);
    }

    /** Compatibility for manually constructed collaborators; Spring always injects configured beans. */
    public static AgentExecutionSettingsPolicy defaults() {
        return new AgentExecutionSettingsPolicy(new TaskExecutionProperties(), new OpenAiChatProperties(),
                new AgentExecutionPolicyProperties());
    }

    public ResolvedAgentExecutionSettings resolve(AgentApp agent) {
        Objects.requireNonNull(agent, "agent must not be null");
        AgentExecutionOptionsResponse options = executionOptions(agent.getModelName());
        if (agent.getMaxTokens() == null || agent.getMaxTokens() < 256 || agent.getMaxTokens() > 100000) {
            throw invalid("maxTokens must be between 256 and 100000");
        }
        int reserve = finalReserve(agent.getMaxTokens());
        Map<String, String> sources = new LinkedHashMap<>();
        int decision = resolveInteger("decisionMaxOutputTokens", agent.getDecisionMaxOutputTokens(),
                options.defaults().decisionMaxOutputTokens(), policy.getMaxOutputTokens(), sources);
        Integer finalOverride = agent.getFinalMaxOutputTokens();
        if (finalOverride != null && finalOverride < reserve) {
            throw invalid("finalMaxOutputTokens must be at least the final reserve of " + reserve);
        }
        Integer configuredFinal = options.defaults().finalMaxOutputTokens();
        int inheritedFinal = configuredFinal == null ? reserve : Math.max(reserve, configuredFinal);
        int finalCap = resolveInteger("finalMaxOutputTokens", finalOverride, inheritedFinal,
                policy.getMaxOutputTokens(), sources);
        if (finalOverride == null && (configuredFinal == null || configuredFinal <= reserve)) {
            sources.put("finalMaxOutputTokens", "FINAL_RESERVE");
        }

        String format = agent.getDecisionResponseFormat();
        if (format == null) {
            format = options.defaults().decisionResponseFormat();
            sources.put("decisionResponseFormat", format.equals(configuredFormat())
                    ? "DEPLOYMENT_DEFAULT" : "DEPLOYMENT_DEFAULT_FALLBACK");
        } else {
            requireFormat(format, options.capabilities());
            sources.put("decisionResponseFormat", "AGENT_OVERRIDE");
        }
        String thinking = agent.getThinkingMode();
        if (thinking == null) {
            thinking = options.defaults().thinkingMode();
            sources.put("thinkingMode", defaults.isProviderThinkingDisabled() && PROVIDER_DEFAULT.equals(thinking)
                    ? "DEPLOYMENT_DEFAULT_FALLBACK" : "DEPLOYMENT_DEFAULT");
        } else {
            if (!THINKING_MODES.contains(thinking)) throw invalid("thinkingMode is unsupported");
            if (DISABLED.equals(thinking) && !options.capabilities().disableThinking()) {
                throw invalid("thinkingMode DISABLED is not verified for the selected model");
            }
            sources.put("thinkingMode", "AGENT_OVERRIDE");
        }
        int timeout = resolveInteger("modelCallTimeoutSeconds", agent.getModelCallTimeoutSeconds(),
                options.defaults().modelCallTimeoutSeconds(), policy.getMaxModelCallTimeoutSeconds(), sources);
        return new ResolvedAgentExecutionSettings(POLICY_VERSION, decision, finalCap, format, thinking,
                timeout, sources);
    }

    public AgentExecutionOptionsResponse executionOptions(String modelName) {
        if (modelName == null || modelName.isBlank() || modelName.trim().length() > 128) {
            throw invalid("modelName must contain 1 to 128 characters");
        }
        String model = modelName.trim();
        validateDeployment();
        var capabilities = new AgentExecutionOptionsResponse.Capabilities(
                policy.getJsonObjectModels().contains(model), policy.getJsonSchemaModels().contains(model),
                policy.getThinkingDisabledModels().contains(model));
        // Inherited transport options must pass the same model capabilities as explicit overrides.
        // Unsupported inherited modes use the legacy request shape; no speculative provider calls occur.
        String format = configuredFormat();
        if ((JSON_OBJECT.equals(format) && !capabilities.jsonObject())
                || (JSON_SCHEMA.equals(format) && !capabilities.jsonSchema())) format = PROMPT_ONLY;
        String thinking = defaults.isProviderThinkingDisabled() && capabilities.disableThinking()
                ? DISABLED : PROVIDER_DEFAULT;
        return new AgentExecutionOptionsResponse(POLICY_VERSION, model,
                new AgentExecutionOptionsResponse.Defaults(defaults.getDecisionMaxOutputTokens(),
                        defaults.getFinalMaxOutputTokens(), format, thinking, defaultTimeoutSeconds()),
                new AgentExecutionOptionsResponse.Limits(policy.getMaxOutputTokens(),
                        policy.getMaxModelCallTimeoutSeconds()), capabilities);
    }

    public static int finalReserve(int maxTotalTokens) {
        return Math.min(2048, Math.max(1, maxTotalTokens / 4));
    }

    private void validateDeployment() {
        if (policy.getMaxOutputTokens() < 2048) {
            throw new IllegalStateException("maxOutputTokens must allow the maximum final reserve of 2048");
        }
        requireDeploymentRange(policy.getMaxOutputTokens(), 16384, "maxOutputTokens");
        requireDeploymentRange(policy.getMaxModelCallTimeoutSeconds(), 600, "maxModelCallTimeoutSeconds");
        requireDeploymentRange(defaults.getDecisionMaxOutputTokens(), policy.getMaxOutputTokens(),
                "decisionMaxOutputTokens default");
        if (defaults.getFinalMaxOutputTokens() != null) {
            requireDeploymentRange(defaults.getFinalMaxOutputTokens(), policy.getMaxOutputTokens(),
                    "finalMaxOutputTokens default");
        }
        requireDeploymentRange(defaultTimeoutSeconds(), policy.getMaxModelCallTimeoutSeconds(),
                "modelCallTimeoutSeconds default");
        if (!defaults.isDecisionFormatExclusive()) {
            throw new IllegalStateException("Decision JSON schema and object defaults are mutually exclusive");
        }
    }

    private int defaultTimeoutSeconds() {
        Duration timeout = chat.getTimeout();
        if (timeout == null || timeout.isNegative() || timeout.isZero()
                || timeout.compareTo(Duration.ofSeconds(600)) > 0) {
            throw new IllegalStateException("LLM timeout default must be positive and at most 600 seconds");
        }
        return Math.toIntExact(timeout.getSeconds() + (timeout.getNano() == 0 ? 0 : 1));
    }

    private String configuredFormat() {
        return defaults.isDecisionJsonSchemaEnabled() ? JSON_SCHEMA
                : defaults.isDecisionJsonObjectEnabled() ? JSON_OBJECT : PROMPT_ONLY;
    }

    private static void requireFormat(String format, AgentExecutionOptionsResponse.Capabilities capabilities) {
        if (!FORMATS.contains(format)) throw invalid("decisionResponseFormat is unsupported");
        if ((JSON_OBJECT.equals(format) && !capabilities.jsonObject())
                || (JSON_SCHEMA.equals(format) && !capabilities.jsonSchema())) {
            throw invalid("decisionResponseFormat " + format + " is not verified for the selected model");
        }
    }

    private static int resolveInteger(String field, Integer override, int inherited, int maximum,
            Map<String, String> sources) {
        int value = override == null ? inherited : override;
        if (value < 1 || value > maximum) {
            throw invalid(field + " must be between 1 and " + maximum);
        }
        sources.put(field, override == null ? "DEPLOYMENT_DEFAULT" : "AGENT_OVERRIDE");
        return value;
    }

    private static void requireDeploymentRange(int value, int maximum, String field) {
        if (value < 1 || value > maximum) {
            throw new IllegalStateException(field + " must be between 1 and " + maximum);
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.COMMON_PARAM_INVALID, message);
    }
}
