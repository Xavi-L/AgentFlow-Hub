package com.agentflow.agent.service;

import static com.agentflow.agent.dto.CreateAgentAppRequest.DEFAULT_MAX_STEPS;
import static com.agentflow.agent.dto.CreateAgentAppRequest.DEFAULT_MAX_TOKENS;
import static com.agentflow.agent.dto.CreateAgentAppRequest.DEFAULT_MAX_TOOL_CALLS;
import static com.agentflow.agent.dto.CreateAgentAppRequest.DEFAULT_TEMPERATURE;
import static com.agentflow.agent.dto.CreateAgentAppRequest.DEFAULT_TIMEOUT_SECONDS;
import static com.agentflow.agent.dto.CreateAgentAppRequest.DEFAULT_TOP_P;

import com.agentflow.agent.dto.AgentAppResponse;
import com.agentflow.agent.dto.AgentAppSummaryResponse;
import com.agentflow.agent.dto.CreateAgentAppRequest;
import com.agentflow.agent.dto.UpdateAgentAppRequest;
import com.agentflow.agent.model.AgentApp;
import com.agentflow.agent.repository.AgentAppMapper;
import com.agentflow.common.api.PageRequest;
import com.agentflow.common.api.PageResult;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.user.security.AuthenticatedUser;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Business boundary for V30-V32 current-owner Agent root-resource operations. */
@Service
public class AgentAppService {
    private static final String ACTIVE_STATUS = "ACTIVE";
    private static final String SUPPORTED_MODEL_PROVIDER = "openai-compatible";

    private final AgentAppMapper agentAppMapper;

    public AgentAppService(AgentAppMapper agentAppMapper) {
        this.agentAppMapper = agentAppMapper;
    }

    /** Creates one ACTIVE Agent whose owner always comes from the authenticated principal. */
    @Transactional
    public AgentAppResponse create(
            AuthenticatedUser currentUser,
            CreateAgentAppRequest request
    ) {
        Objects.requireNonNull(currentUser, "currentUser must not be null");
        Objects.requireNonNull(request, "request must not be null");

        String name = normalizeRequired(request.name(), "name", 128);
        String description = normalizeDescription(request.description());
        String systemPrompt = validateSystemPrompt(request.systemPrompt());
        String modelProvider = validateModelProvider(request.modelProvider());
        String modelName = normalizeRequired(request.modelName(), "modelName", 128);
        BigDecimal temperature = valueOrDefault(request.temperature(), DEFAULT_TEMPERATURE);
        BigDecimal topP = valueOrDefault(request.topP(), DEFAULT_TOP_P);
        int maxSteps = valueOrDefault(request.maxSteps(), DEFAULT_MAX_STEPS);
        int maxToolCalls = valueOrDefault(request.maxToolCalls(), DEFAULT_MAX_TOOL_CALLS);
        int maxTokens = valueOrDefault(request.maxTokens(), DEFAULT_MAX_TOKENS);
        int timeoutSeconds = valueOrDefault(request.timeoutSeconds(), DEFAULT_TIMEOUT_SECONDS);

        validateDecimal(temperature, "temperature", BigDecimal.ZERO, true, new BigDecimal("2"));
        validateDecimal(topP, "topP", BigDecimal.ZERO, false, BigDecimal.ONE);
        validateInteger(maxSteps, "maxSteps", 1, 20);
        validateInteger(maxToolCalls, "maxToolCalls", 0, 20);
        if (maxToolCalls > maxSteps) {
            throw invalid("maxToolCalls must not exceed maxSteps");
        }
        validateInteger(maxTokens, "maxTokens", 256, 100_000);
        validateInteger(timeoutSeconds, "timeoutSeconds", 1, 600);

        AgentApp agentApp = new AgentApp();
        agentApp.setUserId(currentUser.id());
        agentApp.setName(name);
        agentApp.setDescription(description);
        agentApp.setSystemPrompt(systemPrompt);
        agentApp.setModelProvider(modelProvider);
        agentApp.setModelName(modelName);
        agentApp.setTemperature(temperature);
        agentApp.setTopP(topP);
        agentApp.setMaxSteps(maxSteps);
        agentApp.setMaxToolCalls(maxToolCalls);
        agentApp.setMaxTokens(maxTokens);
        agentApp.setTimeoutSeconds(timeoutSeconds);
        agentApp.setStatus(ACTIVE_STATUS);
        // config remains null in the insert model so PostgreSQL applies its '{}'::jsonb default.
        OffsetDateTime now = OffsetDateTime.now();
        agentApp.setCreatedAt(now);
        agentApp.setUpdatedAt(now);

        int affectedRows = agentAppMapper.insert(agentApp);
        if (affectedRows != 1) {
            throw new IllegalStateException("Expected exactly one inserted agent_app row");
        }
        return AgentAppResponse.from(agentApp);
    }

    /**
     * Reads one current-owner, non-deleted Agent. A scoped miss deliberately does not
     * distinguish absent, cross-owner, or soft-deleted rows.
     */
    @Transactional(readOnly = true)
    public AgentAppResponse getOwnedById(
            AuthenticatedUser currentUser,
            Long agentId
    ) {
        Objects.requireNonNull(currentUser, "currentUser must not be null");
        validateAgentId(agentId);

        AgentApp agentApp = agentAppMapper.selectVisibleOwnedById(agentId, currentUser.id());
        if (agentApp == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND, "Agent not found");
        }
        return AgentAppResponse.from(agentApp);
    }

    /**
     * Serializes partial updates to one owner/live Agent, merges omitted fields with the locked
     * row, and skips a write when the normalized effective configuration is unchanged.
     */
    @Transactional
    public AgentAppResponse updateOwnedConfig(
            AuthenticatedUser currentUser,
            Long agentId,
            UpdateAgentAppRequest request
    ) {
        Objects.requireNonNull(currentUser, "currentUser must not be null");
        Objects.requireNonNull(request, "request must not be null");
        validateAgentId(agentId);
        NormalizedConfigUpdate normalized = normalizeConfigUpdate(request);

        AgentApp current = agentAppMapper.selectVisibleOwnedByIdForUpdate(
                agentId,
                currentUser.id()
        );
        if (current == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND, "Agent not found");
        }

        String updatedName = request.namePresent() ? normalized.name() : current.getName();
        String updatedDescription = request.descriptionPresent()
                ? normalized.description()
                : current.getDescription();
        String updatedSystemPrompt = request.systemPromptPresent()
                ? normalized.systemPrompt()
                : current.getSystemPrompt();
        String updatedModelProvider = request.modelProviderPresent()
                ? normalized.modelProvider()
                : current.getModelProvider();
        String updatedModelName = request.modelNamePresent()
                ? normalized.modelName()
                : current.getModelName();
        BigDecimal updatedTemperature = request.temperaturePresent()
                ? normalized.temperature()
                : current.getTemperature();
        BigDecimal updatedTopP = request.topPPresent() ? normalized.topP() : current.getTopP();
        Integer updatedMaxSteps = request.maxStepsPresent()
                ? normalized.maxSteps()
                : current.getMaxSteps();
        Integer updatedMaxToolCalls = request.maxToolCallsPresent()
                ? normalized.maxToolCalls()
                : current.getMaxToolCalls();
        Integer updatedMaxTokens = request.maxTokensPresent()
                ? normalized.maxTokens()
                : current.getMaxTokens();
        Integer updatedTimeoutSeconds = request.timeoutSecondsPresent()
                ? normalized.timeoutSeconds()
                : current.getTimeoutSeconds();

        if (updatedMaxToolCalls > updatedMaxSteps) {
            throw invalid("maxToolCalls must not exceed maxSteps");
        }

        if (sameConfig(
                current,
                updatedName,
                updatedDescription,
                updatedSystemPrompt,
                updatedModelProvider,
                updatedModelName,
                updatedTemperature,
                updatedTopP,
                updatedMaxSteps,
                updatedMaxToolCalls,
                updatedMaxTokens,
                updatedTimeoutSeconds
        )) {
            return AgentAppResponse.from(current);
        }

        current.setName(updatedName);
        current.setDescription(updatedDescription);
        current.setSystemPrompt(updatedSystemPrompt);
        current.setModelProvider(updatedModelProvider);
        current.setModelName(updatedModelName);
        current.setTemperature(updatedTemperature);
        current.setTopP(updatedTopP);
        current.setMaxSteps(updatedMaxSteps);
        current.setMaxToolCalls(updatedMaxToolCalls);
        current.setMaxTokens(updatedMaxTokens);
        current.setTimeoutSeconds(updatedTimeoutSeconds);
        current.setUpdatedAt(OffsetDateTime.now());

        int affectedRows = agentAppMapper.updateConfigOwned(agentId, currentUser.id(), current);
        if (affectedRows == 0) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND, "Agent not found");
        }
        if (affectedRows != 1) {
            throw new IllegalStateException("Expected exactly one updated agent_app row");
        }
        return AgentAppResponse.from(current);
    }

    /** Lists only the current owner's non-deleted Agent summaries; DISABLED remains visible. */
    @Transactional(readOnly = true)
    public PageResult<AgentAppSummaryResponse> listOwnedBy(
            AuthenticatedUser currentUser,
            PageRequest pageRequest
    ) {
        Objects.requireNonNull(currentUser, "currentUser must not be null");
        Objects.requireNonNull(pageRequest, "pageRequest must not be null");

        Page<AgentApp> requestedPage = new Page<>(pageRequest.getPage(), pageRequest.getPageSize());
        IPage<AgentApp> databasePage = agentAppMapper.selectVisibleOwnedPage(
                requestedPage,
                currentUser.id()
        );
        return PageResult.of(
                databasePage.getRecords().stream().map(AgentAppSummaryResponse::from).toList(),
                pageRequest.getPage(),
                pageRequest.getPageSize(),
                databasePage.getTotal()
        );
    }

    private static String normalizeRequired(String value, String fieldName, int maxLength) {
        if (value == null) {
            throw invalid(fieldName + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw invalid(fieldName + " must not be blank");
        }
        if (normalized.length() > maxLength) {
            throw invalid(fieldName + " must not exceed " + maxLength + " characters");
        }
        return normalized;
    }

    private static String normalizeDescription(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > 4_000) {
            throw invalid("description must not exceed 4000 characters");
        }
        return normalized;
    }

    private static String validateSystemPrompt(String value) {
        if (value == null || value.isBlank()) {
            throw invalid("systemPrompt must not be blank");
        }
        if (value.length() > 20_000) {
            throw invalid("systemPrompt must not exceed 20000 characters");
        }
        return value;
    }

    private static String validateModelProvider(String value) {
        if (!SUPPORTED_MODEL_PROVIDER.equals(value)) {
            throw invalid("modelProvider must be openai-compatible");
        }
        return value;
    }

    private static void validateDecimal(
            BigDecimal value,
            String fieldName,
            BigDecimal lowerBound,
            boolean lowerInclusive,
            BigDecimal upperBound
    ) {
        int lowerComparison = value.compareTo(lowerBound);
        if ((lowerInclusive && lowerComparison < 0) || (!lowerInclusive && lowerComparison <= 0)) {
            throw invalid(fieldName + " is below its allowed range");
        }
        if (value.compareTo(upperBound) > 0) {
            throw invalid(fieldName + " exceeds its allowed range");
        }
        if (value.stripTrailingZeros().scale() > 3) {
            throw invalid(fieldName + " must have at most 3 decimal places");
        }
    }

    private static void validateInteger(int value, String fieldName, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw invalid(fieldName + " must be between " + minimum + " and " + maximum);
        }
    }

    private static void validateAgentId(Long agentId) {
        if (agentId == null || agentId <= 0) {
            throw invalid("agentId must be a positive integer");
        }
    }

    private static NormalizedConfigUpdate normalizeConfigUpdate(UpdateAgentAppRequest request) {
        if (!request.hasAnyConfigField()) {
            throw invalid("at least one Agent configuration field is required");
        }

        String name = request.namePresent()
                ? normalizeRequired(request.name(), "name", 128)
                : null;
        String description = request.descriptionPresent()
                ? normalizeDescription(request.description())
                : null;
        String systemPrompt = request.systemPromptPresent()
                ? validateSystemPrompt(request.systemPrompt())
                : null;
        String modelProvider = request.modelProviderPresent()
                ? validateModelProvider(request.modelProvider())
                : null;
        String modelName = request.modelNamePresent()
                ? normalizeRequired(request.modelName(), "modelName", 128)
                : null;

        BigDecimal temperature = null;
        if (request.temperaturePresent()) {
            temperature = requirePatchValue(request.temperature(), "temperature");
            validateDecimal(temperature, "temperature", BigDecimal.ZERO, true, new BigDecimal("2"));
        }

        BigDecimal topP = null;
        if (request.topPPresent()) {
            topP = requirePatchValue(request.topP(), "topP");
            validateDecimal(topP, "topP", BigDecimal.ZERO, false, BigDecimal.ONE);
        }

        Integer maxSteps = null;
        if (request.maxStepsPresent()) {
            maxSteps = requirePatchValue(request.maxSteps(), "maxSteps");
            validateInteger(maxSteps, "maxSteps", 1, 20);
        }

        Integer maxToolCalls = null;
        if (request.maxToolCallsPresent()) {
            maxToolCalls = requirePatchValue(request.maxToolCalls(), "maxToolCalls");
            validateInteger(maxToolCalls, "maxToolCalls", 0, 20);
        }

        Integer maxTokens = null;
        if (request.maxTokensPresent()) {
            maxTokens = requirePatchValue(request.maxTokens(), "maxTokens");
            validateInteger(maxTokens, "maxTokens", 256, 100_000);
        }

        Integer timeoutSeconds = null;
        if (request.timeoutSecondsPresent()) {
            timeoutSeconds = requirePatchValue(request.timeoutSeconds(), "timeoutSeconds");
            validateInteger(timeoutSeconds, "timeoutSeconds", 1, 600);
        }

        return new NormalizedConfigUpdate(
                name,
                description,
                systemPrompt,
                modelProvider,
                modelName,
                temperature,
                topP,
                maxSteps,
                maxToolCalls,
                maxTokens,
                timeoutSeconds
        );
    }

    private static boolean sameConfig(
            AgentApp current,
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
            Integer timeoutSeconds
    ) {
        return Objects.equals(current.getName(), name)
                && Objects.equals(current.getDescription(), description)
                && Objects.equals(current.getSystemPrompt(), systemPrompt)
                && Objects.equals(current.getModelProvider(), modelProvider)
                && Objects.equals(current.getModelName(), modelName)
                && decimalEquals(current.getTemperature(), temperature)
                && decimalEquals(current.getTopP(), topP)
                && Objects.equals(current.getMaxSteps(), maxSteps)
                && Objects.equals(current.getMaxToolCalls(), maxToolCalls)
                && Objects.equals(current.getMaxTokens(), maxTokens)
                && Objects.equals(current.getTimeoutSeconds(), timeoutSeconds);
    }

    private static boolean decimalEquals(BigDecimal left, BigDecimal right) {
        if (left == right) {
            return true;
        }
        return left != null && right != null && left.compareTo(right) == 0;
    }

    private static <T> T requirePatchValue(T value, String fieldName) {
        if (value == null) {
            throw invalid(fieldName + " must not be null");
        }
        return value;
    }

    private static BigDecimal valueOrDefault(BigDecimal value, BigDecimal defaultValue) {
        return value == null ? defaultValue : value;
    }

    private static int valueOrDefault(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.COMMON_PARAM_INVALID, message);
    }

    private record NormalizedConfigUpdate(
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
            Integer timeoutSeconds
    ) {
    }
}
