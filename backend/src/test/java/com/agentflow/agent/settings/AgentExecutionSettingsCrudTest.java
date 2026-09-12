package com.agentflow.agent.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentflow.agent.dto.AgentAppResponse;
import com.agentflow.agent.dto.CreateAgentAppRequest;
import com.agentflow.agent.dto.UpdateAgentAppRequest;
import com.agentflow.agent.engine.TaskExecutionProperties;
import com.agentflow.agent.model.AgentApp;
import com.agentflow.agent.repository.AgentAppMapper;
import com.agentflow.agent.service.AgentAppService;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.config.OpenAiChatProperties;
import com.agentflow.user.security.AuthenticatedUser;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentExecutionSettingsCrudTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final AgentAppMapper mapper = mock(AgentAppMapper.class);
    private final AgentExecutionPolicyProperties limits = new AgentExecutionPolicyProperties();
    private final AgentExecutionSettingsPolicy policy = new AgentExecutionSettingsPolicy(
            new TaskExecutionProperties(), new OpenAiChatProperties(), limits);
    private final AgentAppService service = new AgentAppService(mapper, policy);
    private final AuthenticatedUser owner = new AuthenticatedUser(101L, "owner", "Owner", "USER");

    @Test
    void shouldCreateRoundTripNullableOverridesAndKeepTheAuthenticatedOwner() throws Exception {
        limits.setJsonObjectModels(Set.of("local-model"));
        limits.setThinkingDisabledModels(Set.of("local-model"));
        CreateAgentAppRequest request = json.readValue("""
                {"name":"Agent","systemPrompt":"Answer with evidence","modelProvider":"openai-compatible",
                 "modelName":"local-model","decisionMaxOutputTokens":2048,"finalMaxOutputTokens":4096,
                 "decisionResponseFormat":"JSON_OBJECT","thinkingMode":"DISABLED","modelCallTimeoutSeconds":90}
                """, CreateAgentAppRequest.class);
        when(mapper.insert(any(AgentApp.class))).thenAnswer(call -> {
            call.<AgentApp>getArgument(0).setId(301L);
            return 1;
        });
        AgentAppResponse result = service.create(owner, request);
        var capture = ArgumentCaptor.forClass(AgentApp.class);
        verify(mapper).insert(capture.capture());
        AgentApp saved = capture.getValue();
        assertThat(saved.getUserId()).isEqualTo(101L);
        assertThat(result.decisionMaxOutputTokens()).isEqualTo(2048);
        assertThat(result.finalMaxOutputTokens()).isEqualTo(4096);
        assertThat(result.decisionResponseFormat()).isEqualTo("JSON_OBJECT");
        assertThat(result.thinkingMode()).isEqualTo("DISABLED");
        assertThat(result.modelCallTimeoutSeconds()).isEqualTo(90);
        when(mapper.selectVisibleOwnedById(301L, 101L)).thenReturn(saved);
        assertThat(service.getOwnedById(owner, 301L)).isEqualTo(result);
    }

    @Test
    void shouldPreserveOmittedOverridesAndResetExplicitNullsWithoutRewritingOtherAgents() throws Exception {
        AgentApp current = current();
        current.setDecisionMaxOutputTokens(2048);
        current.setFinalMaxOutputTokens(4096);
        current.setDecisionResponseFormat("PROMPT_ONLY");
        current.setThinkingMode("PROVIDER_DEFAULT");
        current.setModelCallTimeoutSeconds(90);
        when(mapper.selectVisibleOwnedByIdForUpdate(301L, 101L)).thenReturn(current);
        when(mapper.updateConfigOwned(eq(301L), eq(101L), any())).thenReturn(1);
        var untouched = service.updateOwnedConfig(owner, 301L, patch("{\"name\":\"Renamed\"}"));
        assertThat(untouched.decisionMaxOutputTokens()).isEqualTo(2048);
        assertThat(untouched.finalMaxOutputTokens()).isEqualTo(4096);
        assertThat(untouched.modelCallTimeoutSeconds()).isEqualTo(90);
        var reset = service.updateOwnedConfig(owner, 301L, patch("""
                {"decisionMaxOutputTokens":null,"finalMaxOutputTokens":null,"decisionResponseFormat":null,
                 "thinkingMode":null,"modelCallTimeoutSeconds":null}
                """));
        assertThat(reset.decisionMaxOutputTokens()).isNull();
        assertThat(reset.finalMaxOutputTokens()).isNull();
        assertThat(reset.decisionResponseFormat()).isNull();
        assertThat(reset.thinkingMode()).isNull();
        assertThat(reset.modelCallTimeoutSeconds()).isNull();
        assertThat(current.getName()).isEqualTo("Renamed");
        json.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        var payload = json.valueToTree(reset);
        for (String field : List.of("decisionMaxOutputTokens", "finalMaxOutputTokens", "decisionResponseFormat",
                "thinkingMode", "modelCallTimeoutSeconds")) assertThat(payload.path(field).isNull()).isTrue();
    }

    @Test
    void shouldValidateModelAndBudgetChangesAgainstMergedOverridesBeforeWriting() throws Exception {
        limits.setJsonSchemaModels(Set.of("local-model"));
        AgentApp current = current();
        current.setDecisionResponseFormat("JSON_SCHEMA");
        current.setFinalMaxOutputTokens(2000);
        when(mapper.selectVisibleOwnedByIdForUpdate(301L, 101L)).thenReturn(current);
        assertThatThrownBy(() -> service.updateOwnedConfig(owner, 301L, patch("{\"modelName\":\"unverified\"}")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("not verified");
        assertThatThrownBy(() -> service.updateOwnedConfig(owner, 301L, patch("{\"maxTokens\":12000}")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("2048");
        assertThat(current.getModelName()).isEqualTo("local-model");
        assertThat(current.getMaxTokens()).isEqualTo(8000);
        verify(mapper, never()).updateConfigOwned(any(), any(), any());
        when(mapper.updateConfigOwned(eq(301L), eq(101L), any())).thenReturn(1);
        var repaired = service.updateOwnedConfig(owner, 301L,
                patch("{\"modelName\":\"unverified\",\"decisionResponseFormat\":null}"));
        assertThat(repaired.modelName()).isEqualTo("unverified");
        assertThat(repaired.decisionResponseFormat()).isNull();
    }

    @Test
    void shouldKeepCrossOwnerAndDeletedRowsUnavailableToAdvancedUpdates() {
        assertThatThrownBy(() -> service.updateOwnedConfig(owner, 301L, patch("{\"decisionMaxOutputTokens\":1024}")))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.COMMON_NOT_FOUND));
        verify(mapper).selectVisibleOwnedByIdForUpdate(301L, 101L);
        verify(mapper, never()).updateConfigOwned(any(), any(), any());
    }

    @Test
    void shouldDistinguishNullFromAbsentAndRejectCoercionAndUnlistedFields() throws Exception {
        assertThat(patch("{\"name\":\"Agent\"}").decisionMaxOutputTokensPresent()).isFalse();
        var reset = patch("{\"decisionMaxOutputTokens\":null}");
        assertThat(reset.decisionMaxOutputTokensPresent()).isTrue();
        assertThat(reset.decisionMaxOutputTokens()).isNull();
        for (String invalid : List.of("{\"decisionMaxOutputTokens\":\"512\"}",
                "{\"finalMaxOutputTokens\":3.5}", "{\"modelCallTimeoutSeconds\":2147483648}",
                "{\"thinkingMode\":true}", "{\"decisionResponseFormat\":{}}", "{\"sources\":{}}")) {
            assertThatThrownBy(() -> patch(invalid)).isInstanceOf(JsonProcessingException.class);
            assertThatThrownBy(() -> json.readValue(invalid, CreateAgentAppRequest.class))
                    .isInstanceOf(JsonProcessingException.class);
        }
        assertThatThrownBy(() -> service.updateOwnedConfig(owner, 301L, patch("{\"timeoutSeconds\":null}")))
                .hasMessageContaining("timeoutSeconds must not be null");
    }

    private UpdateAgentAppRequest patch(String source) throws JsonProcessingException {
        return json.readValue(source, UpdateAgentAppRequest.class);
    }

    private static AgentApp current() {
        AgentApp value = new AgentApp();
        value.setId(301L);
        value.setName("Agent");
        value.setSystemPrompt("Use evidence");
        value.setModelProvider("openai-compatible");
        value.setModelName("local-model");
        value.setTemperature(new BigDecimal("0.2"));
        value.setTopP(new BigDecimal("0.8"));
        value.setMaxSteps(6);
        value.setMaxToolCalls(4);
        value.setMaxTokens(8000);
        value.setTimeoutSeconds(120);
        value.setStatus("ACTIVE");
        return value;
    }
}
