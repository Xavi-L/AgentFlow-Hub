package com.agentflow.agent.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentflow.agent.dto.AgentAppResponse;
import com.agentflow.agent.dto.AgentAppSummaryResponse;
import com.agentflow.agent.dto.CreateAgentAppRequest;
import com.agentflow.agent.dto.UpdateAgentAppRequest;
import com.agentflow.agent.repository.AgentAppMapper;
import com.agentflow.agent.service.AgentAppService;
import com.agentflow.common.api.ApiResponse;
import com.agentflow.common.api.PageRequest;
import com.agentflow.common.api.PageResult;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.common.error.GlobalExceptionHandler;
import com.agentflow.common.web.TraceIdFilter;
import com.agentflow.user.security.AuthenticatedUser;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AgentAppControllerTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldCreateThroughTheUnified201Response() {
        AgentAppService service = Mockito.mock(AgentAppService.class);
        AgentAppController controller = new AgentAppController(service);
        AuthenticatedUser currentUser = currentUser();
        CreateAgentAppRequest request = minimalRequest();
        when(service.create(currentUser, request)).thenReturn(fullResponse());

        ResponseEntity<ApiResponse<AgentAppResponse>> response = controller.create(currentUser, request);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("OK");
        assertThat(response.getBody().getMessage()).isEqualTo("Agent created");
        assertThat(response.getBody().getData().id()).isEqualTo("301");
        verify(service).create(currentUser, request);
    }

    @Test
    void shouldGetThroughTheUnified200Response() {
        AgentAppService service = Mockito.mock(AgentAppService.class);
        AgentAppController controller = new AgentAppController(service);
        AuthenticatedUser currentUser = currentUser();
        when(service.getOwnedById(currentUser, 301L)).thenReturn(fullResponse());

        ApiResponse<AgentAppResponse> response = controller.get(currentUser, 301L);

        assertThat(response.getCode()).isEqualTo("OK");
        assertThat(response.getMessage()).isEqualTo("Agent retrieved");
        assertThat(response.getData().id()).isEqualTo("301");
        verify(service).getOwnedById(currentUser, 301L);
    }

    @Test
    void shouldUpdateThroughTheUnified200Response() {
        AgentAppService service = Mockito.mock(AgentAppService.class);
        AgentAppController controller = new AgentAppController(service);
        AuthenticatedUser currentUser = currentUser();
        UpdateAgentAppRequest request = new UpdateAgentAppRequest(
                Set.of("name"),
                "Payment diagnosis agent V2",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
        when(service.updateOwnedConfig(currentUser, 301L, request)).thenReturn(fullResponse());

        ApiResponse<AgentAppResponse> response = controller.update(currentUser, 301L, request);

        assertThat(response.getCode()).isEqualTo("OK");
        assertThat(response.getMessage()).isEqualTo("Agent updated");
        assertThat(response.getData().id()).isEqualTo("301");
        verify(service).updateOwnedConfig(currentUser, 301L, request);
    }

    @Test
    void shouldEnableAndDisableThroughTheUnified200Responses() {
        AgentAppService service = Mockito.mock(AgentAppService.class);
        AgentAppController controller = new AgentAppController(service);
        AuthenticatedUser currentUser = currentUser();
        when(service.enableOwned(currentUser, 301L)).thenReturn(fullResponse("ACTIVE"));
        when(service.disableOwned(currentUser, 301L)).thenReturn(fullResponse("DISABLED"));

        ApiResponse<AgentAppResponse> enabled = controller.enable(currentUser, 301L);
        ApiResponse<AgentAppResponse> disabled = controller.disable(currentUser, 301L);

        assertThat(enabled.getCode()).isEqualTo("OK");
        assertThat(enabled.getMessage()).isEqualTo("Agent enabled");
        assertThat(enabled.getData().status()).isEqualTo("ACTIVE");
        assertThat(disabled.getCode()).isEqualTo("OK");
        assertThat(disabled.getMessage()).isEqualTo("Agent disabled");
        assertThat(disabled.getData().status()).isEqualTo("DISABLED");
        verify(service).enableOwned(currentUser, 301L);
        verify(service).disableOwned(currentUser, 301L);
    }

    @Test
    void shouldSoftDeleteThroughTheUnified200Response() {
        AgentAppService service = Mockito.mock(AgentAppService.class);
        AgentAppController controller = new AgentAppController(service);
        AuthenticatedUser currentUser = currentUser();

        ApiResponse<Void> response = controller.softDelete(currentUser, 301L);

        assertThat(response.getCode()).isEqualTo("OK");
        assertThat(response.getMessage()).isEqualTo("Agent deleted");
        assertThat(response.getData()).isNull();
        verify(service).softDeleteOwned(currentUser, 301L);
    }

    @Test
    void shouldBindThePrincipalAndReturnOnlyTheSafeFullCreateResponse() throws Exception {
        AgentAppService service = Mockito.mock(AgentAppService.class);
        AuthenticatedUser currentUser = authenticate();
        when(service.create(eq(currentUser), any(CreateAgentAppRequest.class))).thenReturn(fullResponse());

        mockMvc(service).perform(post("/api/v1/agents")
                        .header("X-Trace-Id", "af-test-agent-create")
                        .contentType("application/json")
                        .content("""
                                {
                                  "name":"Payment diagnosis agent",
                                  "systemPrompt":"Diagnose payment failures.",
                                  "modelProvider":"openai-compatible",
                                  "modelName":"kimi-k2"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").value("Agent created"))
                .andExpect(jsonPath("$.data.id").value("301"))
                .andExpect(jsonPath("$.data.temperature").value(0.2))
                .andExpect(jsonPath("$.data.topP").value(0.8))
                .andExpect(jsonPath("$.data.maxSteps").value(6))
                .andExpect(jsonPath("$.data.maxToolCalls").value(4))
                .andExpect(jsonPath("$.data.maxTokens").value(8000))
                .andExpect(jsonPath("$.data.timeoutSeconds").value(120))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.userId").doesNotExist())
                .andExpect(jsonPath("$.data.config").doesNotExist())
                .andExpect(jsonPath("$.data.currentPromptVersionId").doesNotExist())
                .andExpect(jsonPath("$.data.deletedAt").doesNotExist());

        ArgumentCaptor<CreateAgentAppRequest> requestCaptor = ArgumentCaptor.forClass(
                CreateAgentAppRequest.class
        );
        verify(service).create(eq(currentUser), requestCaptor.capture());
        assertThat(requestCaptor.getValue().temperature()).isNull();
        assertThat(requestCaptor.getValue().maxSteps()).isNull();
    }

    @Test
    void shouldReturnOnlyCompactCurrentOwnerListItems() throws Exception {
        AgentAppService service = Mockito.mock(AgentAppService.class);
        AuthenticatedUser currentUser = authenticate();
        PageResult<AgentAppSummaryResponse> page = PageResult.of(
                List.of(summaryResponse("401", "ACTIVE"), summaryResponse("400", "DISABLED")),
                1,
                20,
                2
        );
        when(service.listOwnedBy(eq(currentUser), any(PageRequest.class))).thenReturn(page);

        mockMvc(service).perform(get("/api/v1/agents?page=1&pageSize=20")
                        .header("X-Trace-Id", "af-test-agent-list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].id").value("401"))
                .andExpect(jsonPath("$.data.items[1].status").value("DISABLED"))
                .andExpect(jsonPath("$.data.items[0].systemPrompt").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].temperature").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].maxSteps").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].userId").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].config").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].deletedAt").doesNotExist());

        verify(service).listOwnedBy(eq(currentUser), any(PageRequest.class));
    }

    @Test
    void shouldBindThePrincipalAndReturnTheCompleteSafeDetailResponse() throws Exception {
        AgentAppService service = Mockito.mock(AgentAppService.class);
        AuthenticatedUser currentUser = authenticate();
        when(service.getOwnedById(currentUser, 301L)).thenReturn(fullResponse());

        mockMvc(service).perform(get("/api/v1/agents/301")
                        .header("X-Trace-Id", "af-test-agent-detail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").value("Agent retrieved"))
                .andExpect(jsonPath("$.data.id").value("301"))
                .andExpect(jsonPath("$.data.name").value("Payment diagnosis agent"))
                .andExpect(jsonPath("$.data.description").value("Analyze payment failures."))
                .andExpect(jsonPath("$.data.systemPrompt").value("Diagnose payment failures."))
                .andExpect(jsonPath("$.data.modelProvider").value("openai-compatible"))
                .andExpect(jsonPath("$.data.modelName").value("kimi-k2"))
                .andExpect(jsonPath("$.data.temperature").value(0.2))
                .andExpect(jsonPath("$.data.topP").value(0.8))
                .andExpect(jsonPath("$.data.maxSteps").value(6))
                .andExpect(jsonPath("$.data.maxToolCalls").value(4))
                .andExpect(jsonPath("$.data.maxTokens").value(8000))
                .andExpect(jsonPath("$.data.timeoutSeconds").value(120))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.createdAt").exists())
                .andExpect(jsonPath("$.data.updatedAt").exists())
                .andExpect(jsonPath("$.data.userId").doesNotExist())
                .andExpect(jsonPath("$.data.config").doesNotExist())
                .andExpect(jsonPath("$.data.deletedAt").doesNotExist())
                .andExpect(jsonPath("$.data.currentPromptVersionId").doesNotExist())
                .andExpect(jsonPath("$.data.knowledgeBaseIds").doesNotExist())
                .andExpect(jsonPath("$.data.toolIds").doesNotExist());

        verify(service).getOwnedById(currentUser, 301L);
    }

    @Test
    void shouldBindPatchPresenceAndReturnTheCompleteSafeUpdatedResponse() throws Exception {
        AgentAppService service = Mockito.mock(AgentAppService.class);
        AuthenticatedUser currentUser = authenticate();
        when(service.updateOwnedConfig(eq(currentUser), eq(301L), any(UpdateAgentAppRequest.class)))
                .thenReturn(fullResponse());

        mockMvc(service).perform(patch("/api/v1/agents/301")
                        .header("X-Trace-Id", "af-test-agent-update")
                        .contentType("application/json")
                        .content("""
                                {
                                  "name":"Payment diagnosis agent V2",
                                  "description":null,
                                  "temperature":0.3,
                                  "maxSteps":8,
                                  "maxToolCalls":5
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").value("Agent updated"))
                .andExpect(jsonPath("$.data.id").value("301"))
                .andExpect(jsonPath("$.data.systemPrompt").value("Diagnose payment failures."))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.userId").doesNotExist())
                .andExpect(jsonPath("$.data.config").doesNotExist())
                .andExpect(jsonPath("$.data.deletedAt").doesNotExist())
                .andExpect(jsonPath("$.data.currentPromptVersionId").doesNotExist())
                .andExpect(jsonPath("$.data.knowledgeBaseIds").doesNotExist())
                .andExpect(jsonPath("$.data.toolIds").doesNotExist());

        ArgumentCaptor<UpdateAgentAppRequest> requestCaptor = ArgumentCaptor.forClass(
                UpdateAgentAppRequest.class
        );
        verify(service).updateOwnedConfig(eq(currentUser), eq(301L), requestCaptor.capture());
        UpdateAgentAppRequest request = requestCaptor.getValue();
        assertThat(request.presentFields()).containsExactlyInAnyOrder(
                "name",
                "description",
                "temperature",
                "maxSteps",
                "maxToolCalls"
        );
        assertThat(request.descriptionPresent()).isTrue();
        assertThat(request.description()).isNull();
        assertThat(request.systemPromptPresent()).isFalse();
        assertThat(request.temperature()).isEqualByComparingTo("0.3");
    }

    @Test
    void shouldBindBodylessEnableAndDisableRoutesAndReturnTheCompleteSafeState() throws Exception {
        AgentAppService service = Mockito.mock(AgentAppService.class);
        AuthenticatedUser currentUser = authenticate();
        when(service.disableOwned(currentUser, 301L)).thenReturn(fullResponse("DISABLED"));
        when(service.enableOwned(currentUser, 301L)).thenReturn(fullResponse("ACTIVE"));

        mockMvc(service).perform(post("/api/v1/agents/301/disable")
                        .header("X-Trace-Id", "af-test-agent-disable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").value("Agent disabled"))
                .andExpect(jsonPath("$.data.id").value("301"))
                .andExpect(jsonPath("$.data.name").value("Payment diagnosis agent"))
                .andExpect(jsonPath("$.data.description").value("Analyze payment failures."))
                .andExpect(jsonPath("$.data.systemPrompt").value("Diagnose payment failures."))
                .andExpect(jsonPath("$.data.modelProvider").value("openai-compatible"))
                .andExpect(jsonPath("$.data.modelName").value("kimi-k2"))
                .andExpect(jsonPath("$.data.temperature").value(0.2))
                .andExpect(jsonPath("$.data.topP").value(0.8))
                .andExpect(jsonPath("$.data.maxSteps").value(6))
                .andExpect(jsonPath("$.data.maxToolCalls").value(4))
                .andExpect(jsonPath("$.data.maxTokens").value(8000))
                .andExpect(jsonPath("$.data.timeoutSeconds").value(120))
                .andExpect(jsonPath("$.data.status").value("DISABLED"))
                .andExpect(jsonPath("$.data.createdAt").exists())
                .andExpect(jsonPath("$.data.updatedAt").exists())
                .andExpect(jsonPath("$.data.userId").doesNotExist())
                .andExpect(jsonPath("$.data.config").doesNotExist())
                .andExpect(jsonPath("$.data.deletedAt").doesNotExist())
                .andExpect(jsonPath("$.data.currentPromptVersionId").doesNotExist())
                .andExpect(jsonPath("$.data.knowledgeBaseIds").doesNotExist())
                .andExpect(jsonPath("$.data.toolIds").doesNotExist())
                .andExpect(jsonPath("$.traceId").value("af-test-agent-disable"));

        mockMvc(service).perform(post("/api/v1/agents/301/enable")
                        .header("X-Trace-Id", "af-test-agent-enable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").value("Agent enabled"))
                .andExpect(jsonPath("$.data.id").value("301"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.userId").doesNotExist())
                .andExpect(jsonPath("$.data.config").doesNotExist())
                .andExpect(jsonPath("$.data.deletedAt").doesNotExist());

        verify(service).disableOwned(currentUser, 301L);
        verify(service).enableOwned(currentUser, 301L);
    }

    @Test
    void shouldBindThePrincipalToTheBodylessSoftDeleteRouteAndReturnNullData() throws Exception {
        AgentAppService service = Mockito.mock(AgentAppService.class);
        AuthenticatedUser currentUser = authenticate();

        mockMvc(service).perform(delete("/api/v1/agents/301")
                        .header("X-Trace-Id", "af-test-agent-delete"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").value("Agent deleted"))
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.traceId").value("af-test-agent-delete"))
                .andExpect(jsonPath("$.timestamp").exists());

        verify(service).softDeleteOwned(currentUser, 301L);
    }

    @Test
    void shouldReturnTheUniformNotFoundResponseForAnInvisibleAgentDelete() throws Exception {
        AgentAppService service = Mockito.mock(AgentAppService.class);
        AuthenticatedUser currentUser = authenticate();
        Mockito.doThrow(new BusinessException(ErrorCode.COMMON_NOT_FOUND, "Agent not found"))
                .when(service).softDeleteOwned(currentUser, 999L);

        mockMvc(service).perform(delete("/api/v1/agents/999")
                        .header("X-Trace-Id", "af-test-agent-delete-missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Agent not found"));

        verify(service).softDeleteOwned(currentUser, 999L);
    }

    @Test
    void shouldReturnTheUniformNotFoundResponseForInvisibleStatusActions() throws Exception {
        AgentAppService service = Mockito.mock(AgentAppService.class);
        AuthenticatedUser currentUser = authenticate();
        when(service.enableOwned(currentUser, 999L)).thenThrow(
                new BusinessException(ErrorCode.COMMON_NOT_FOUND, "Agent not found")
        );
        when(service.disableOwned(currentUser, 999L)).thenThrow(
                new BusinessException(ErrorCode.COMMON_NOT_FOUND, "Agent not found")
        );

        for (String action : List.of("enable", "disable")) {
            mockMvc(service).perform(post("/api/v1/agents/999/" + action)
                            .header("X-Trace-Id", "af-test-agent-status-missing-" + action))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"))
                    .andExpect(jsonPath("$.message").value("Agent not found"));
        }

        verify(service).enableOwned(currentUser, 999L);
        verify(service).disableOwned(currentUser, 999L);
    }

    @Test
    void shouldReturnTheUniformNotFoundResponseForAnInvisibleAgentUpdate() throws Exception {
        AgentAppService service = Mockito.mock(AgentAppService.class);
        AuthenticatedUser currentUser = authenticate();
        when(service.updateOwnedConfig(eq(currentUser), eq(999L), any(UpdateAgentAppRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.COMMON_NOT_FOUND, "Agent not found"));

        mockMvc(service).perform(patch("/api/v1/agents/999")
                        .header("X-Trace-Id", "af-test-agent-update-missing")
                        .contentType("application/json")
                        .content("{\"name\":\"Renamed\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Agent not found"));

        verify(service).updateOwnedConfig(eq(currentUser), eq(999L), any(UpdateAgentAppRequest.class));
    }

    @Test
    void shouldReturnTheUniformNotFoundResponseForAnInvisibleAgent() throws Exception {
        AgentAppService service = Mockito.mock(AgentAppService.class);
        AuthenticatedUser currentUser = authenticate();
        when(service.getOwnedById(currentUser, 999L)).thenThrow(
                new BusinessException(ErrorCode.COMMON_NOT_FOUND, "Agent not found")
        );

        mockMvc(service).perform(get("/api/v1/agents/999")
                        .header("X-Trace-Id", "af-test-agent-detail-missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Agent not found"));

        verify(service).getOwnedById(currentUser, 999L);
    }

    @Test
    void shouldRejectNonNumericAndOutOfLongRangeIdsBeforeCallingTheService() throws Exception {
        AgentAppService service = Mockito.mock(AgentAppService.class);
        authenticate();

        for (String invalidId : List.of("not-a-number", "9223372036854775808")) {
            mockMvc(service).perform(get("/api/v1/agents/" + invalidId)
                            .header("X-Trace-Id", "af-test-agent-detail-invalid-binding"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_PARAM_INVALID"));
        }

        verifyNoInteractions(service);
    }

    @Test
    void shouldRejectInvalidPatchPathIdsBeforeCallingTheService() throws Exception {
        AgentAppService service = Mockito.mock(AgentAppService.class);
        authenticate();

        for (String invalidId : List.of("not-a-number", "9223372036854775808")) {
            mockMvc(service).perform(patch("/api/v1/agents/" + invalidId)
                            .header("X-Trace-Id", "af-test-agent-update-invalid-binding")
                            .contentType("application/json")
                            .content("{\"name\":\"Renamed\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_PARAM_INVALID"));
        }

        verifyNoInteractions(service);
    }

    @Test
    void shouldRejectNonNumericAndOutOfLongRangeDeleteIdsBeforeCallingTheService()
            throws Exception {
        AgentAppService service = Mockito.mock(AgentAppService.class);
        authenticate();

        for (String invalidId : List.of("not-a-number", "9223372036854775808")) {
            mockMvc(service).perform(delete("/api/v1/agents/" + invalidId)
                            .header("X-Trace-Id", "af-test-agent-delete-invalid-binding"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_PARAM_INVALID"));
        }

        verifyNoInteractions(service);
    }

    @Test
    void shouldRejectNonNumericAndOutOfLongRangeStatusActionIdsBeforeCallingTheService()
            throws Exception {
        AgentAppService service = Mockito.mock(AgentAppService.class);
        authenticate();

        for (String action : List.of("enable", "disable")) {
            for (String invalidId : List.of("not-a-number", "9223372036854775808")) {
                mockMvc(service).perform(post("/api/v1/agents/" + invalidId + "/" + action)
                                .header("X-Trace-Id", "af-test-agent-status-invalid-binding"))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("COMMON_PARAM_INVALID"));
            }
        }

        verifyNoInteractions(service);
    }

    @Test
    void shouldRejectEveryNonConfigPatchFieldBeforeCallingTheService() throws Exception {
        AgentAppService service = Mockito.mock(AgentAppService.class);
        authenticate();

        for (String forbiddenField : List.of(
                "id",
                "userId",
                "status",
                "config",
                "createdAt",
                "updatedAt",
                "deletedAt",
                "currentPromptVersionId",
                "knowledgeBaseIds",
                "toolIds"
        )) {
            mockMvc(service).perform(patch("/api/v1/agents/301")
                            .header("X-Trace-Id", "af-test-agent-update-forbidden-" + forbiddenField)
                            .contentType("application/json")
                            .content("{\"%s\":\"client-controlled\"}".formatted(forbiddenField)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_REQUEST_BODY_INVALID"));
        }

        verifyNoInteractions(service);
    }

    @Test
    void shouldRejectMalformedNonObjectAndWrongTypePatchBodiesBeforeCallingTheService()
            throws Exception {
        AgentAppService service = Mockito.mock(AgentAppService.class);
        authenticate();

        for (String invalidBody : List.of(
                "[]",
                "\"text\"",
                "{\"name\":123}",
                "{\"temperature\":\"0.3\"}",
                "{\"maxSteps\":1.5}",
                "{"
        )) {
            mockMvc(service).perform(patch("/api/v1/agents/301")
                            .header("X-Trace-Id", "af-test-agent-update-invalid-body")
                            .contentType("application/json")
                            .content(invalidBody))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_REQUEST_BODY_INVALID"));
        }

        verifyNoInteractions(service);
    }

    @Test
    void shouldRejectEmptyOrNonDescriptionNullPatchAsParamInvalidBeforeMapperAccess()
            throws Exception {
        AgentAppMapper mapper = Mockito.mock(AgentAppMapper.class);
        AgentAppService service = new AgentAppService(mapper);
        authenticate();

        for (String invalidBody : List.of(
                "{}",
                "{\"name\":null}",
                "{\"systemPrompt\":null}",
                "{\"temperature\":null}",
                "{\"maxSteps\":null}"
        )) {
            mockMvc(service).perform(patch("/api/v1/agents/301")
                            .header("X-Trace-Id", "af-test-agent-update-invalid-value")
                            .contentType("application/json")
                            .content(invalidBody))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_PARAM_INVALID"));
        }

        verifyNoInteractions(mapper);
    }

    @Test
    void shouldRejectNonPositiveIdsBeforeAccessingTheMapper() throws Exception {
        AgentAppMapper mapper = Mockito.mock(AgentAppMapper.class);
        AgentAppService service = new AgentAppService(mapper);
        authenticate();

        for (String invalidId : List.of("0", "-1")) {
            mockMvc(service).perform(get("/api/v1/agents/" + invalidId)
                            .header("X-Trace-Id", "af-test-agent-detail-non-positive"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_PARAM_INVALID"));
        }

        verifyNoInteractions(mapper);
    }

    @Test
    void shouldRejectNonPositiveDeleteIdsBeforeAccessingTheMapper() throws Exception {
        AgentAppMapper mapper = Mockito.mock(AgentAppMapper.class);
        AgentAppService service = new AgentAppService(mapper);
        authenticate();

        for (String invalidId : List.of("0", "-1")) {
            mockMvc(service).perform(delete("/api/v1/agents/" + invalidId)
                            .header("X-Trace-Id", "af-test-agent-delete-non-positive"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_PARAM_INVALID"));
        }

        verifyNoInteractions(mapper);
    }

    @Test
    void shouldRejectNonPositiveStatusActionIdsBeforeAccessingTheMapper() throws Exception {
        AgentAppMapper mapper = Mockito.mock(AgentAppMapper.class);
        AgentAppService service = new AgentAppService(mapper);
        authenticate();

        for (String action : List.of("enable", "disable")) {
            for (String invalidId : List.of("0", "-1")) {
                mockMvc(service).perform(post("/api/v1/agents/" + invalidId + "/" + action)
                                .header("X-Trace-Id", "af-test-agent-status-non-positive"))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("COMMON_PARAM_INVALID"));
            }
        }

        verifyNoInteractions(mapper);
    }

    @Test
    void shouldRejectEveryServerOwnedOrFutureBindingFieldBeforeCallingTheService() throws Exception {
        AgentAppService service = Mockito.mock(AgentAppService.class);
        authenticate();

        for (String forbiddenField : List.of(
                "id",
                "userId",
                "status",
                "config",
                "currentPromptVersionId",
                "createdAt",
                "updatedAt",
                "deletedAt",
                "knowledgeBaseIds",
                "toolIds"
        )) {
            mockMvc(service).perform(post("/api/v1/agents")
                            .header("X-Trace-Id", "af-test-agent-forbidden-" + forbiddenField)
                            .contentType("application/json")
                            .content("""
                                    {
                                      "name":"Agent",
                                      "systemPrompt":"Prompt",
                                      "modelProvider":"openai-compatible",
                                      "modelName":"model",
                                      "%s":"client-controlled"
                                    }
                                    """.formatted(forbiddenField)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_REQUEST_BODY_INVALID"));
        }

        verifyNoInteractions(service);
    }

    @Test
    void shouldRejectWrongJsonTypesBeforeCallingTheService() throws Exception {
        AgentAppService service = Mockito.mock(AgentAppService.class);
        authenticate();
        List<String> invalidBodies = List.of(
                validBodyWith("\"name\":123"),
                validBodyWith("\"temperature\":\"0.2\""),
                validBodyWith("\"topP\":true"),
                validBodyWith("\"maxSteps\":1.5")
        );

        for (String invalidBody : invalidBodies) {
            mockMvc(service).perform(post("/api/v1/agents")
                            .header("X-Trace-Id", "af-test-agent-wrong-type")
                            .contentType("application/json")
                            .content(invalidBody))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_REQUEST_BODY_INVALID"));
        }

        verifyNoInteractions(service);
    }

    @Test
    void shouldRejectBlankUnsupportedOutOfRangeAndCrossFieldValuesBeforeCallingTheService()
            throws Exception {
        AgentAppService service = Mockito.mock(AgentAppService.class);
        authenticate();
        List<String> invalidBodies = List.of(
                body(" ", "Prompt", "openai-compatible", "model", ""),
                body("Agent", " ", "openai-compatible", "model", ""),
                body("Agent", "Prompt", "other", "model", ""),
                body("Agent", "Prompt", "openai-compatible", " ", ""),
                body("Agent", "Prompt", "openai-compatible", "model", ",\"temperature\":2.001"),
                body("Agent", "Prompt", "openai-compatible", "model", ",\"topP\":0"),
                body("Agent", "Prompt", "openai-compatible", "model", ",\"maxSteps\":6,\"maxToolCalls\":6"),
                body("Agent", "Prompt", "openai-compatible", "model", ",\"maxSteps\":6,\"maxToolCalls\":7"),
                body("Agent", "Prompt", "openai-compatible", "model", ",\"maxTokens\":255"),
                body("Agent", "Prompt", "openai-compatible", "model", ",\"timeoutSeconds\":601")
        );

        for (String invalidBody : invalidBodies) {
            mockMvc(service).perform(post("/api/v1/agents")
                            .header("X-Trace-Id", "af-test-agent-invalid")
                            .contentType("application/json")
                            .content(invalidBody))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_PARAM_INVALID"));
        }

        verifyNoInteractions(service);
    }

    private static String validBodyWith(String replacementField) {
        String nameField = replacementField.startsWith("\"name\"")
                ? replacementField
                : "\"name\":\"Agent\"," + replacementField;
        return """
                {
                  %s,
                  "systemPrompt":"Prompt",
                  "modelProvider":"openai-compatible",
                  "modelName":"model"
                }
                """.formatted(nameField);
    }

    private static String body(
            String name,
            String systemPrompt,
            String modelProvider,
            String modelName,
            String extraFields
    ) {
        return """
                {
                  "name":"%s",
                  "systemPrompt":"%s",
                  "modelProvider":"%s",
                  "modelName":"%s"%s
                }
                """.formatted(name, systemPrompt, modelProvider, modelName, extraFields);
    }

    private static CreateAgentAppRequest minimalRequest() {
        return new CreateAgentAppRequest(
                "Payment diagnosis agent",
                null,
                "Diagnose payment failures.",
                "openai-compatible",
                "kimi-k2",
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private static AgentAppResponse fullResponse() {
        return fullResponse("ACTIVE");
    }

    private static AgentAppResponse fullResponse(String status) {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-31T10:00:00+08:00");
        return new AgentAppResponse(
                "301",
                "Payment diagnosis agent",
                "Analyze payment failures.",
                "Diagnose payment failures.",
                "openai-compatible",
                "kimi-k2",
                new BigDecimal("0.2"),
                new BigDecimal("0.8"),
                6,
                4,
                8_000,
                120,
                status,
                now,
                now
        );
    }

    private static AgentAppSummaryResponse summaryResponse(String id, String status) {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-31T10:00:00+08:00");
        return new AgentAppSummaryResponse(
                id,
                "Agent " + id,
                "Summary",
                "openai-compatible",
                "kimi-k2",
                status,
                now,
                now
        );
    }

    private static AuthenticatedUser authenticate() {
        AuthenticatedUser currentUser = currentUser();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(currentUser, "test", List.of())
        );
        return currentUser;
    }

    private static AuthenticatedUser currentUser() {
        return new AuthenticatedUser(101L, "xavier_01", "Xavier", "USER");
    }

    private static MockMvc mockMvc(AgentAppService service) {
        return MockMvcBuilders
                .standaloneSetup(new AgentAppController(service))
                .addPlaceholderValue("agentflow.api.prefix", "/api/v1")
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .addFilters(new TraceIdFilter())
                .build();
    }
}
