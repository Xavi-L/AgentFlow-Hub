package com.agentflow.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.agentflow.agent.dto.AgentAppResponse;
import com.agentflow.agent.dto.AgentAppSummaryResponse;
import com.agentflow.agent.dto.CreateAgentAppRequest;
import com.agentflow.agent.model.AgentApp;
import com.agentflow.agent.repository.AgentAppMapper;
import com.agentflow.common.api.PageRequest;
import com.agentflow.common.api.PageResult;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.user.security.AuthenticatedUser;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentAppServiceTest {

    @Mock
    private AgentAppMapper agentAppMapper;

    @InjectMocks
    private AgentAppService agentAppService;

    @Captor
    private ArgumentCaptor<AgentApp> agentAppCaptor;

    @Test
    void shouldCreateAnOwnedActiveAgentWithTheFrozenV30Defaults() {
        AuthenticatedUser currentUser = currentUser();
        CreateAgentAppRequest request = request(
                "  Payment diagnosis agent  ",
                "   ",
                "  Preserve this prompt exactly.\n",
                "openai-compatible",
                "  kimi-k2  ",
                null,
                null,
                null,
                null,
                null,
                null
        );
        when(agentAppMapper.insert(any(AgentApp.class))).thenAnswer(invocation -> {
            AgentApp agentApp = invocation.getArgument(0);
            agentApp.setId(301L);
            return 1;
        });

        AgentAppResponse response = agentAppService.create(currentUser, request);

        verify(agentAppMapper).insert(agentAppCaptor.capture());
        AgentApp persisted = agentAppCaptor.getValue();
        assertThat(persisted.getUserId()).isEqualTo(currentUser.id());
        assertThat(persisted.getName()).isEqualTo("Payment diagnosis agent");
        assertThat(persisted.getDescription()).isNull();
        assertThat(persisted.getSystemPrompt()).isEqualTo("  Preserve this prompt exactly.\n");
        assertThat(persisted.getModelProvider()).isEqualTo("openai-compatible");
        assertThat(persisted.getModelName()).isEqualTo("kimi-k2");
        assertThat(persisted.getTemperature()).isEqualByComparingTo("0.2");
        assertThat(persisted.getTopP()).isEqualByComparingTo("0.8");
        assertThat(persisted.getMaxSteps()).isEqualTo(6);
        assertThat(persisted.getMaxToolCalls()).isEqualTo(4);
        assertThat(persisted.getMaxTokens()).isEqualTo(8_000);
        assertThat(persisted.getTimeoutSeconds()).isEqualTo(120);
        assertThat(persisted.getStatus()).isEqualTo("ACTIVE");
        assertThat(persisted.getConfig()).isNull();
        assertThat(persisted.getDeletedAt()).isNull();
        assertThat(persisted.getCreatedAt()).isNotNull();
        assertThat(persisted.getUpdatedAt()).isEqualTo(persisted.getCreatedAt());
        assertThat(response.id()).isEqualTo("301");
        assertThat(response.temperature()).isEqualByComparingTo("0.2");
        assertThat(response.topP()).isEqualByComparingTo("0.8");
        assertThat(response.status()).isEqualTo("ACTIVE");
        verifyNoMoreInteractions(agentAppMapper);
    }

    @Test
    void shouldPersistExplicitConfigurationWithinTheFrozenBounds() {
        CreateAgentAppRequest request = request(
                "Agent",
                "Description",
                "System prompt",
                "openai-compatible",
                "qwen3",
                new BigDecimal("2.000"),
                new BigDecimal("1.000"),
                20,
                20,
                100_000,
                600
        );
        when(agentAppMapper.insert(any(AgentApp.class))).thenAnswer(invocation -> {
            AgentApp agentApp = invocation.getArgument(0);
            agentApp.setId(302L);
            return 1;
        });

        AgentAppResponse response = agentAppService.create(currentUser(), request);

        verify(agentAppMapper).insert(agentAppCaptor.capture());
        AgentApp persisted = agentAppCaptor.getValue();
        assertThat(persisted.getTemperature()).isEqualByComparingTo("2");
        assertThat(persisted.getTopP()).isEqualByComparingTo("1");
        assertThat(persisted.getMaxSteps()).isEqualTo(20);
        assertThat(persisted.getMaxToolCalls()).isEqualTo(20);
        assertThat(persisted.getMaxTokens()).isEqualTo(100_000);
        assertThat(persisted.getTimeoutSeconds()).isEqualTo(600);
        assertThat(response.temperature()).isEqualByComparingTo("2");
        assertThat(response.topP()).isEqualByComparingTo("1");
    }

    @Test
    void shouldRejectInvalidTextProviderNumericAndCrossFieldInputsBeforeInsert() {
        List<CreateAgentAppRequest> invalidRequests = List.of(
                request(" ", null, "Prompt", "openai-compatible", "model", null, null, null, null, null, null),
                request("x".repeat(129), null, "Prompt", "openai-compatible", "model", null, null, null, null, null, null),
                request("Agent", "x".repeat(4_001), "Prompt", "openai-compatible", "model", null, null, null, null, null, null),
                request("Agent", null, " ", "openai-compatible", "model", null, null, null, null, null, null),
                request("Agent", null, "x".repeat(20_001), "openai-compatible", "model", null, null, null, null, null, null),
                request("Agent", null, "Prompt", "other", "model", null, null, null, null, null, null),
                request("Agent", null, "Prompt", "openai-compatible", " ", null, null, null, null, null, null),
                request("Agent", null, "Prompt", "openai-compatible", "x".repeat(129), null, null, null, null, null, null),
                request("Agent", null, "Prompt", "openai-compatible", "model", new BigDecimal("-0.001"), null, null, null, null, null),
                request("Agent", null, "Prompt", "openai-compatible", "model", new BigDecimal("0.0001"), null, null, null, null, null),
                request("Agent", null, "Prompt", "openai-compatible", "model", new BigDecimal("2.001"), null, null, null, null, null),
                request("Agent", null, "Prompt", "openai-compatible", "model", null, BigDecimal.ZERO, null, null, null, null),
                request("Agent", null, "Prompt", "openai-compatible", "model", null, new BigDecimal("1.001"), null, null, null, null),
                request("Agent", null, "Prompt", "openai-compatible", "model", null, null, 0, null, null, null),
                request("Agent", null, "Prompt", "openai-compatible", "model", null, null, 21, null, null, null),
                request("Agent", null, "Prompt", "openai-compatible", "model", null, null, null, -1, null, null),
                request("Agent", null, "Prompt", "openai-compatible", "model", null, null, 6, 7, null, null),
                request("Agent", null, "Prompt", "openai-compatible", "model", null, null, null, 7, null, null),
                request("Agent", null, "Prompt", "openai-compatible", "model", null, null, null, null, 255, null),
                request("Agent", null, "Prompt", "openai-compatible", "model", null, null, null, null, 100_001, null),
                request("Agent", null, "Prompt", "openai-compatible", "model", null, null, null, null, null, 0),
                request("Agent", null, "Prompt", "openai-compatible", "model", null, null, null, null, null, 601)
        );

        for (CreateAgentAppRequest invalidRequest : invalidRequests) {
            assertThatThrownBy(() -> agentAppService.create(currentUser(), invalidRequest))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                            .isEqualTo(ErrorCode.COMMON_PARAM_INVALID));
        }

        verify(agentAppMapper, never()).insert(any(AgentApp.class));
        verifyNoInteractions(agentAppMapper);
    }

    @Test
    void shouldListOnlyMapperScopedSummariesAndKeepDisabledRowsVisible() {
        AgentApp active = summaryRow(401L, "Newest", "ACTIVE");
        AgentApp disabled = summaryRow(400L, "Disabled", "DISABLED");
        when(agentAppMapper.selectVisibleOwnedPage(any(), eq(101L))).thenAnswer(invocation -> {
            Page<AgentApp> page = invocation.getArgument(0);
            page.setRecords(List.of(active, disabled));
            page.setTotal(3L);
            return page;
        });

        PageResult<AgentAppSummaryResponse> result = agentAppService.listOwnedBy(
                currentUser(),
                new PageRequest(1, 2)
        );

        verify(agentAppMapper).selectVisibleOwnedPage(any(), eq(101L));
        assertThat(result.getItems()).extracting(AgentAppSummaryResponse::id)
                .containsExactly("401", "400");
        assertThat(result.getItems()).extracting(AgentAppSummaryResponse::status)
                .containsExactly("ACTIVE", "DISABLED");
        assertThat(result.getPage()).isEqualTo(1);
        assertThat(result.getPageSize()).isEqualTo(2);
        assertThat(result.getTotal()).isEqualTo(3L);
        assertThat(result.isHasNext()).isTrue();
        verifyNoMoreInteractions(agentAppMapper);
    }

    @Test
    void shouldReturnTheCompletePublicConfigurationAndKeepDisabledVisible() {
        AgentApp row = detailRow(9_223_372_036_854_775_000L, "DISABLED");
        when(agentAppMapper.selectVisibleOwnedById(row.getId(), 101L)).thenReturn(row);

        AgentAppResponse response = agentAppService.getOwnedById(currentUser(), row.getId());

        assertThat(response.id()).isEqualTo("9223372036854775000");
        assertThat(response.name()).isEqualTo("Payment diagnosis agent");
        assertThat(response.description()).isEqualTo("Analyze payment failures");
        assertThat(response.systemPrompt()).isEqualTo("Use only supplied payment facts.");
        assertThat(response.modelProvider()).isEqualTo("openai-compatible");
        assertThat(response.modelName()).isEqualTo("kimi-k2");
        assertThat(response.temperature()).isEqualByComparingTo("0.2");
        assertThat(response.topP()).isEqualByComparingTo("0.8");
        assertThat(response.maxSteps()).isEqualTo(6);
        assertThat(response.maxToolCalls()).isEqualTo(4);
        assertThat(response.maxTokens()).isEqualTo(8_000);
        assertThat(response.timeoutSeconds()).isEqualTo(120);
        assertThat(response.status()).isEqualTo("DISABLED");
        assertThat(response.createdAt()).isEqualTo(row.getCreatedAt());
        assertThat(response.updatedAt()).isEqualTo(row.getUpdatedAt());
        verify(agentAppMapper).selectVisibleOwnedById(row.getId(), 101L);
        verifyNoMoreInteractions(agentAppMapper);
    }

    @Test
    void shouldMapEveryScopedMissToTheSameNotFoundContract() {
        when(agentAppMapper.selectVisibleOwnedById(999L, 101L)).thenReturn(null);

        assertThatThrownBy(() -> agentAppService.getOwnedById(currentUser(), 999L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Agent not found")
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.COMMON_NOT_FOUND));

        verify(agentAppMapper).selectVisibleOwnedById(999L, 101L);
        verifyNoMoreInteractions(agentAppMapper);
    }

    @Test
    void shouldRejectMissingOrNonPositiveAgentIdBeforeMapperAccess() {
        for (Long invalidId : java.util.Arrays.asList(null, 0L, -1L)) {
            assertThatThrownBy(() -> agentAppService.getOwnedById(currentUser(), invalidId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("agentId must be a positive integer")
                    .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                            .isEqualTo(ErrorCode.COMMON_PARAM_INVALID));
        }

        verifyNoInteractions(agentAppMapper);
    }

    private static CreateAgentAppRequest request(
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
        return new CreateAgentAppRequest(
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

    private static AgentApp summaryRow(Long id, String name, String status) {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-31T10:00:00+08:00");
        AgentApp row = new AgentApp();
        row.setId(id);
        row.setName(name);
        row.setDescription("Summary");
        row.setModelProvider("openai-compatible");
        row.setModelName("kimi-k2");
        row.setStatus(status);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    private static AgentApp detailRow(Long id, String status) {
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-09-01T10:00:00+08:00");
        AgentApp row = new AgentApp();
        row.setId(id);
        row.setUserId(101L);
        row.setName("Payment diagnosis agent");
        row.setDescription("Analyze payment failures");
        row.setSystemPrompt("Use only supplied payment facts.");
        row.setModelProvider("openai-compatible");
        row.setModelName("kimi-k2");
        row.setTemperature(new BigDecimal("0.200"));
        row.setTopP(new BigDecimal("0.800"));
        row.setMaxSteps(6);
        row.setMaxToolCalls(4);
        row.setMaxTokens(8_000);
        row.setTimeoutSeconds(120);
        row.setStatus(status);
        row.setConfig("{\"internal\":true}");
        row.setCreatedAt(createdAt);
        row.setUpdatedAt(createdAt.plusMinutes(1));
        return row;
    }

    private static AuthenticatedUser currentUser() {
        return new AuthenticatedUser(101L, "xavier_01", "Xavier", "USER");
    }
}
