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
import com.agentflow.agent.dto.UpdateAgentAppRequest;
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
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
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

    @Captor
    private ArgumentCaptor<OffsetDateTime> deletedAtCaptor;

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

    @Test
    void shouldMergeAFullConfigPatchAndKeepDisabledStatusAndInternalFieldsUntouched() {
        AgentApp current = detailRow(301L, "DISABLED");
        OffsetDateTime createdAt = current.getCreatedAt();
        when(agentAppMapper.selectVisibleOwnedByIdForUpdate(301L, 101L)).thenReturn(current);
        when(agentAppMapper.updateConfigOwned(eq(301L), eq(101L), any(AgentApp.class)))
                .thenReturn(1);
        UpdateAgentAppRequest request = updateRequest(
                Set.of(
                        "name",
                        "description",
                        "systemPrompt",
                        "modelProvider",
                        "modelName",
                        "temperature",
                        "topP",
                        "maxSteps",
                        "maxToolCalls",
                        "maxTokens",
                        "timeoutSeconds"
                ),
                "  Payment diagnosis agent V2  ",
                "  Analyze timeout failures  ",
                "  Preserve the updated prompt exactly.\n",
                "openai-compatible",
                "  qwen3  ",
                new BigDecimal("0.300"),
                new BigDecimal("0.900"),
                8,
                5,
                9_000,
                180
        );

        AgentAppResponse response = agentAppService.updateOwnedConfig(currentUser(), 301L, request);

        verify(agentAppMapper).selectVisibleOwnedByIdForUpdate(301L, 101L);
        verify(agentAppMapper).updateConfigOwned(eq(301L), eq(101L), agentAppCaptor.capture());
        AgentApp updated = agentAppCaptor.getValue();
        assertThat(updated.getName()).isEqualTo("Payment diagnosis agent V2");
        assertThat(updated.getDescription()).isEqualTo("Analyze timeout failures");
        assertThat(updated.getSystemPrompt()).isEqualTo("  Preserve the updated prompt exactly.\n");
        assertThat(updated.getModelProvider()).isEqualTo("openai-compatible");
        assertThat(updated.getModelName()).isEqualTo("qwen3");
        assertThat(updated.getTemperature()).isEqualByComparingTo("0.3");
        assertThat(updated.getTopP()).isEqualByComparingTo("0.9");
        assertThat(updated.getMaxSteps()).isEqualTo(8);
        assertThat(updated.getMaxToolCalls()).isEqualTo(5);
        assertThat(updated.getMaxTokens()).isEqualTo(9_000);
        assertThat(updated.getTimeoutSeconds()).isEqualTo(180);
        assertThat(updated.getStatus()).isEqualTo("DISABLED");
        assertThat(updated.getConfig()).isEqualTo("{\"internal\":true}");
        assertThat(updated.getCreatedAt()).isEqualTo(createdAt);
        assertThat(updated.getDeletedAt()).isNull();
        assertThat(response.status()).isEqualTo("DISABLED");
        assertThat(response.updatedAt()).isAfter(createdAt);
        verifyNoMoreInteractions(agentAppMapper);
    }

    @Test
    void shouldPreserveEveryOmittedFieldInsteadOfApplyingCreateDefaults() {
        AgentApp current = detailRow(301L, "ACTIVE");
        current.setTemperature(new BigDecimal("1.100"));
        current.setTopP(new BigDecimal("0.600"));
        current.setMaxSteps(15);
        current.setMaxToolCalls(12);
        current.setMaxTokens(42_000);
        current.setTimeoutSeconds(480);
        when(agentAppMapper.selectVisibleOwnedByIdForUpdate(301L, 101L)).thenReturn(current);
        when(agentAppMapper.updateConfigOwned(eq(301L), eq(101L), any(AgentApp.class)))
                .thenReturn(1);

        AgentAppResponse response = agentAppService.updateOwnedConfig(
                currentUser(),
                301L,
                updateRequest(
                        Set.of("name"),
                        "Renamed only",
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
                )
        );

        verify(agentAppMapper).updateConfigOwned(eq(301L), eq(101L), agentAppCaptor.capture());
        AgentApp updated = agentAppCaptor.getValue();
        assertThat(updated.getName()).isEqualTo("Renamed only");
        assertThat(updated.getDescription()).isEqualTo("Analyze payment failures");
        assertThat(updated.getSystemPrompt()).isEqualTo("Use only supplied payment facts.");
        assertThat(updated.getModelProvider()).isEqualTo("openai-compatible");
        assertThat(updated.getModelName()).isEqualTo("kimi-k2");
        assertThat(updated.getTemperature()).isEqualByComparingTo("1.1");
        assertThat(updated.getTopP()).isEqualByComparingTo("0.6");
        assertThat(updated.getMaxSteps()).isEqualTo(15);
        assertThat(updated.getMaxToolCalls()).isEqualTo(12);
        assertThat(updated.getMaxTokens()).isEqualTo(42_000);
        assertThat(updated.getTimeoutSeconds()).isEqualTo(480);
        assertThat(response.temperature()).isEqualByComparingTo("1.1");
        assertThat(response.maxSteps()).isEqualTo(15);
        verify(agentAppMapper).selectVisibleOwnedByIdForUpdate(301L, 101L);
        verifyNoMoreInteractions(agentAppMapper);
    }

    @Test
    void shouldClearDescriptionForExplicitNullOrBlank() {
        for (String description : java.util.Arrays.asList(null, "  \t  ")) {
            AgentApp current = detailRow(301L, "ACTIVE");
            when(agentAppMapper.selectVisibleOwnedByIdForUpdate(301L, 101L)).thenReturn(current);
            when(agentAppMapper.updateConfigOwned(eq(301L), eq(101L), any(AgentApp.class)))
                    .thenReturn(1);

            AgentAppResponse response = agentAppService.updateOwnedConfig(
                    currentUser(),
                    301L,
                    updateRequest(
                            Set.of("description"),
                            null,
                            description,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null
                    )
            );

            assertThat(response.description()).isNull();
        }

        verify(agentAppMapper, org.mockito.Mockito.times(2))
                .selectVisibleOwnedByIdForUpdate(301L, 101L);
        verify(agentAppMapper, org.mockito.Mockito.times(2))
                .updateConfigOwned(eq(301L), eq(101L), any(AgentApp.class));
        verifyNoMoreInteractions(agentAppMapper);
    }

    @Test
    void shouldSkipUpdateAndTimestampRefreshForNormalizedAndScaleEquivalentValues() {
        AgentApp current = detailRow(301L, "ACTIVE");
        OffsetDateTime previousUpdatedAt = current.getUpdatedAt();
        when(agentAppMapper.selectVisibleOwnedByIdForUpdate(301L, 101L)).thenReturn(current);

        AgentAppResponse response = agentAppService.updateOwnedConfig(
                currentUser(),
                301L,
                updateRequest(
                        Set.of("name", "description", "temperature", "topP"),
                        "  Payment diagnosis agent  ",
                        "  Analyze payment failures  ",
                        null,
                        null,
                        null,
                        new BigDecimal("0.2"),
                        new BigDecimal("0.8000"),
                        null,
                        null,
                        null,
                        null
                )
        );

        assertThat(response.updatedAt()).isEqualTo(previousUpdatedAt);
        assertThat(response.temperature()).isEqualByComparingTo("0.2");
        verify(agentAppMapper).selectVisibleOwnedByIdForUpdate(301L, 101L);
        verify(agentAppMapper, never()).updateConfigOwned(any(), any(), any());
        verifyNoMoreInteractions(agentAppMapper);
    }

    @Test
    void shouldValidateEachSingleBudgetChangeAgainstTheLockedStoredCounterpart() {
        when(agentAppMapper.selectVisibleOwnedByIdForUpdate(301L, 101L))
                .thenReturn(detailRow(301L, "ACTIVE"), detailRow(301L, "ACTIVE"));
        List<UpdateAgentAppRequest> invalidRequests = List.of(
                updateRequest(
                        Set.of("maxSteps"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        3,
                        null,
                        null,
                        null
                ),
                updateRequest(
                        Set.of("maxToolCalls"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        7,
                        null,
                        null
                )
        );

        for (UpdateAgentAppRequest invalidRequest : invalidRequests) {
            assertThatThrownBy(() -> agentAppService.updateOwnedConfig(
                    currentUser(),
                    301L,
                    invalidRequest
            )).isInstanceOf(BusinessException.class)
                    .hasMessage("maxToolCalls must not exceed maxSteps")
                    .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                            .isEqualTo(ErrorCode.COMMON_PARAM_INVALID));
        }

        verify(agentAppMapper, org.mockito.Mockito.times(2))
                .selectVisibleOwnedByIdForUpdate(301L, 101L);
        verify(agentAppMapper, never()).updateConfigOwned(any(), any(), any());
        verifyNoMoreInteractions(agentAppMapper);
    }

    @Test
    void shouldRejectEmptyNullAndOutOfRangeRecognizedFieldsBeforeLocking() {
        List<UpdateAgentAppRequest> invalidRequests = List.of(
                updateRequest(Set.of(), null, null, null, null, null, null, null, null, null, null, null),
                updateRequest(Set.of("name"), null, null, null, null, null, null, null, null, null, null, null),
                updateRequest(Set.of("name"), " ", null, null, null, null, null, null, null, null, null, null),
                updateRequest(Set.of("description"), null, "x".repeat(4_001), null, null, null, null, null, null, null, null, null),
                updateRequest(Set.of("systemPrompt"), null, null, " ", null, null, null, null, null, null, null, null),
                updateRequest(Set.of("modelProvider"), null, null, null, "other", null, null, null, null, null, null, null),
                updateRequest(Set.of("modelName"), null, null, null, null, " ", null, null, null, null, null, null),
                updateRequest(Set.of("temperature"), null, null, null, null, null, null, null, null, null, null, null),
                updateRequest(Set.of("temperature"), null, null, null, null, null, new BigDecimal("0.0001"), null, null, null, null, null),
                updateRequest(Set.of("topP"), null, null, null, null, null, null, BigDecimal.ZERO, null, null, null, null),
                updateRequest(Set.of("maxSteps"), null, null, null, null, null, null, null, 0, null, null, null),
                updateRequest(Set.of("maxToolCalls"), null, null, null, null, null, null, null, null, -1, null, null),
                updateRequest(Set.of("maxTokens"), null, null, null, null, null, null, null, null, null, 255, null),
                updateRequest(Set.of("timeoutSeconds"), null, null, null, null, null, null, null, null, null, null, 601)
        );

        for (UpdateAgentAppRequest invalidRequest : invalidRequests) {
            assertThatThrownBy(() -> agentAppService.updateOwnedConfig(
                    currentUser(),
                    301L,
                    invalidRequest
            )).isInstanceOf(BusinessException.class)
                    .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                            .isEqualTo(ErrorCode.COMMON_PARAM_INVALID));
        }

        verifyNoInteractions(agentAppMapper);
    }

    @Test
    void shouldMapAnEmptyLockedScopeAndZeroRowWriteToTheUniformNotFoundContract() {
        UpdateAgentAppRequest request = updateRequest(
                Set.of("name"),
                "Renamed",
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
        when(agentAppMapper.selectVisibleOwnedByIdForUpdate(999L, 101L)).thenReturn(null);

        assertThatThrownBy(() -> agentAppService.updateOwnedConfig(currentUser(), 999L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Agent not found")
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.COMMON_NOT_FOUND));

        AgentApp current = detailRow(301L, "ACTIVE");
        when(agentAppMapper.selectVisibleOwnedByIdForUpdate(301L, 101L)).thenReturn(current);
        when(agentAppMapper.updateConfigOwned(eq(301L), eq(101L), any(AgentApp.class)))
                .thenReturn(0);

        assertThatThrownBy(() -> agentAppService.updateOwnedConfig(currentUser(), 301L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Agent not found")
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.COMMON_NOT_FOUND));

        verify(agentAppMapper).selectVisibleOwnedByIdForUpdate(999L, 101L);
        verify(agentAppMapper).selectVisibleOwnedByIdForUpdate(301L, 101L);
        verify(agentAppMapper).updateConfigOwned(eq(301L), eq(101L), any(AgentApp.class));
        verifyNoMoreInteractions(agentAppMapper);
    }

    @Test
    void shouldSoftDeleteOneOwnedLiveAgentWithOneServerTimestampAndNoPriorRead() {
        when(agentAppMapper.softDeleteOwned(eq(301L), eq(101L), any(OffsetDateTime.class)))
                .thenReturn(1);

        agentAppService.softDeleteOwned(currentUser(), 301L);

        verify(agentAppMapper).softDeleteOwned(eq(301L), eq(101L), deletedAtCaptor.capture());
        assertThat(deletedAtCaptor.getValue()).isNotNull();
        verifyNoMoreInteractions(agentAppMapper);
    }

    @Test
    void shouldMapEveryZeroRowDeleteToTheSameNotFoundContract() {
        when(agentAppMapper.softDeleteOwned(eq(999L), eq(101L), any(OffsetDateTime.class)))
                .thenReturn(0);

        for (String invisibleCase : List.of(
                "missing",
                "foreign-owner",
                "already-soft-deleted",
                "repeated-delete"
        )) {
            assertThatThrownBy(() -> agentAppService.softDeleteOwned(currentUser(), 999L))
                    .as(invisibleCase)
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Agent not found")
                    .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                            .isEqualTo(ErrorCode.COMMON_NOT_FOUND));
        }

        verify(agentAppMapper, org.mockito.Mockito.times(4))
                .softDeleteOwned(eq(999L), eq(101L), any(OffsetDateTime.class));
        verifyNoMoreInteractions(agentAppMapper);
    }

    @Test
    void shouldRejectMissingOrNonPositiveDeleteIdsBeforeMapperAccess() {
        for (Long invalidId : java.util.Arrays.asList(null, 0L, -1L)) {
            assertThatThrownBy(() -> agentAppService.softDeleteOwned(currentUser(), invalidId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("agentId must be a positive integer")
                    .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                            .isEqualTo(ErrorCode.COMMON_PARAM_INVALID));
        }

        verifyNoInteractions(agentAppMapper);
    }

    @Test
    void shouldTreatAnUnexpectedDeleteRowCountAsAnInternalConsistencyFailure() {
        when(agentAppMapper.softDeleteOwned(eq(301L), eq(101L), any(OffsetDateTime.class)))
                .thenReturn(2);

        assertThatThrownBy(() -> agentAppService.softDeleteOwned(currentUser(), 301L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Expected exactly one soft-deleted agent_app row");

        verify(agentAppMapper).softDeleteOwned(eq(301L), eq(101L), any(OffsetDateTime.class));
        verifyNoMoreInteractions(agentAppMapper);
    }

    @Test
    void shouldKeepADeletedAgentInvisibleToExistingDetailPatchAndListScopes() {
        AtomicBoolean softDeleted = new AtomicBoolean(false);
        when(agentAppMapper.softDeleteOwned(eq(301L), eq(101L), any(OffsetDateTime.class)))
                .thenAnswer(invocation -> softDeleted.compareAndSet(false, true) ? 1 : 0);
        when(agentAppMapper.selectVisibleOwnedById(301L, 101L))
                .thenAnswer(invocation -> softDeleted.get() ? null : detailRow(301L, "ACTIVE"));
        when(agentAppMapper.selectVisibleOwnedByIdForUpdate(301L, 101L))
                .thenAnswer(invocation -> softDeleted.get() ? null : detailRow(301L, "ACTIVE"));
        when(agentAppMapper.selectVisibleOwnedPage(any(), eq(101L))).thenAnswer(invocation -> {
            Page<AgentApp> page = invocation.getArgument(0);
            page.setRecords(softDeleted.get()
                    ? List.of()
                    : List.of(summaryRow(301L, "Visible before deletion", "ACTIVE")));
            page.setTotal(softDeleted.get() ? 0 : 1);
            return page;
        });

        agentAppService.softDeleteOwned(currentUser(), 301L);

        assertThatThrownBy(() -> agentAppService.getOwnedById(currentUser(), 301L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Agent not found");
        assertThatThrownBy(() -> agentAppService.updateOwnedConfig(
                currentUser(),
                301L,
                updateRequest(
                        Set.of("name"),
                        "Cannot revive",
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
                )
        )).isInstanceOf(BusinessException.class)
                .hasMessage("Agent not found");
        PageResult<AgentAppSummaryResponse> page = agentAppService.listOwnedBy(
                currentUser(),
                new PageRequest(1, 20)
        );
        assertThat(page.getItems()).isEmpty();
        assertThat(page.getTotal()).isZero();

        verify(agentAppMapper).softDeleteOwned(eq(301L), eq(101L), any(OffsetDateTime.class));
        verify(agentAppMapper).selectVisibleOwnedById(301L, 101L);
        verify(agentAppMapper).selectVisibleOwnedByIdForUpdate(301L, 101L);
        verify(agentAppMapper).selectVisibleOwnedPage(any(), eq(101L));
        verifyNoMoreInteractions(agentAppMapper);
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

    private static UpdateAgentAppRequest updateRequest(
            Set<String> presentFields,
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
        return new UpdateAgentAppRequest(
                presentFields,
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
