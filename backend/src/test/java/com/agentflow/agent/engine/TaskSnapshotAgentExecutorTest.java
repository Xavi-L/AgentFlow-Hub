package com.agentflow.agent.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.agentflow.agent.rag.SnapshotRagResult;
import com.agentflow.agent.rag.SnapshotRagService;
import com.agentflow.agent.snapshot.AgentTaskExecutionSnapshot;
import com.agentflow.agent.settings.ResolvedAgentExecutionSettings;
import com.agentflow.agent.task.execution.*;
import com.agentflow.agent.task.model.*;
import com.agentflow.agent.task.service.AgentTaskLifecycleTransactionService;
import com.agentflow.agent.trace.*;
import com.agentflow.infra.llm.*;
import com.agentflow.tool.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class TaskSnapshotAgentExecutorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final SnapshotRagService rag = mock(SnapshotRagService.class);
    private final LlmGateway gateway = mock(LlmGateway.class);
    private final ToolRuntime tools = mock(ToolRuntime.class);
    private final ExecutionRecorder recorder = mock(ExecutionRecorder.class);
    private final ExecutionRecorderFactory factory = mock(ExecutionRecorderFactory.class);
    private final AgentTaskLifecycleTransactionService lifecycle = mock(AgentTaskLifecycleTransactionService.class);
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-05T00:00:00Z"));
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Clock clock = new Clock() {
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now.get(); }
    };
    private TaskSnapshotAgentExecutor executor;
    private final List<LlmCallRecord> llmLogs = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(factory.open(101)).thenReturn(recorder);
        when(lifecycle.changePhase(eq(101L), any())).thenReturn(true);
        AtomicInteger steps = new AtomicInteger();
        when(recorder.startStep(any(), anyString())).thenAnswer(call -> {
            int index = steps.getAndIncrement();
            return new StepHandle(101, 1000 + index, index, call.getArgument(0));
        });
        doAnswer(call -> { llmLogs.add(call.getArgument(0)); return null; }).when(recorder).recordLlmCall(any());
        when(rag.retrieve(any(), any())).thenReturn(new SnapshotRagResult("", List.of(), 0, 0, "NONE"));
        when(tools.execute(any())).thenAnswer(call -> {
            ToolExecutionCommand command = call.getArgument(0);
            String code = command.toolId() == 11L ? "order_query" : "payment_log_query";
            return ToolExecutionResult.success(code, "Known result", mapper.createObjectNode().put("status", "OK"), 2);
        });
        executor = executor(new TaskExecutionProperties());
    }

    @Test
    void executesFrozenRagDecisionToolsAndSeparateFinalGenerationWithOrderedFacts() throws Exception {
        when(rag.retrieve(any(), any())).thenReturn(evidence());
        script(call("order_query", "{\"orderNo\":\"A\"}"), call("payment_log_query", "{}"), finish(), "Answer [S1]");
        TaskExecutionRequest request = request(5, 3, 50000);
        TaskExecutionOutcome outcome = executor.execute(request);
        assertThat(outcome.resultType()).isEqualTo(TaskExecutionResultType.COMPLETED);
        assertThat(outcome.decisionTurnsUsed()).isEqualTo(3);
        assertThat(outcome.toolCallsUsed()).isEqualTo(2);
        assertThat(outcome.tokenUsage().totalTokens()).isEqualTo(40);
        assertThat(outcome.citations().get(0).path("citationId").asText()).isEqualTo("S1");
        assertThat(llmLogs).extracting(LlmCallRecord::callType).containsExactly(
                LlmCallType.DECISION, LlmCallType.DECISION, LlmCallType.DECISION, LlmCallType.FINAL_GENERATION);
        ArgumentCaptor<ToolExecutionCommand> commands = ArgumentCaptor.forClass(ToolExecutionCommand.class);
        verify(tools, times(2)).execute(commands.capture());
        assertThat(commands.getAllValues()).allSatisfy(command -> {
            assertThat(command.taskId()).isEqualTo(101);
            assertThat(command.stepId()).isPositive();
            assertThat(command.taskScope().snapshot()).isEqualTo(request.executionSnapshot());
            assertThat(command.taskScope().deadlineAt()).isEqualTo(request.deadlineAt());
        });
        ArgumentCaptor<LlmChatRequest> requests = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(gateway, times(4)).chat(requests.capture());
        assertThat(requests.getAllValues()).allSatisfy(modelRequest -> {
            assertThat(modelRequest.responseSchema()).isNull();
            assertThat(modelRequest.responseFormat()).isNull();
            assertThat(modelRequest.thinkingMode()).isNull();
            assertThat(modelRequest.modelProvider()).isEqualTo("frozen-provider");
            assertThat(modelRequest.modelName()).isEqualTo("frozen-model");
            assertThat(modelRequest.messages().get(0).content()).isEqualTo("Frozen task system prompt");
            assertThat(modelRequest.messages().get(2).content()).contains("S1", "UNTRUSTED_DATA");
        });
        assertThat(requests.getAllValues().subList(0, 3)).allSatisfy(modelRequest -> {
            assertThat(modelRequest.messages().get(1).role()).isEqualTo(LlmMessageRole.SYSTEM);
            assertThat(modelRequest.messages().get(1).content()).contains(
                    "already completed successfully", "what information is still missing",
                    "reused=true", "it does not refresh data", "return FINISH for separate final generation");
        });
        JsonNode firstPayload = mapper.readTree(requests.getAllValues().get(0).messages().get(2).content());
        JsonNode secondPayload = mapper.readTree(requests.getAllValues().get(1).messages().get(2).content());
        JsonNode thirdPayload = mapper.readTree(requests.getAllValues().get(2).messages().get(2).content());
        assertThat(firstPayload.path("observations").size()).isZero();
        assertThat(firstPayload.path("availableTools").size()).isEqualTo(2);
        assertThat(firstPayload.path("availableTools").get(1).path("toolCode").asText()).isEqualTo("payment_log_query");
        assertThat(secondPayload.path("observations").size()).isEqualTo(1);
        JsonNode orderObservation = secondPayload.path("observations").get(0);
        assertThat(orderObservation.path("toolCode").asText()).isEqualTo("order_query");
        assertThat(thirdPayload.path("observations").size()).isEqualTo(2);
        assertThat(thirdPayload.path("observations").get(0)).isEqualTo(orderObservation);
        JsonNode paymentObservation = thirdPayload.path("observations").get(1);
        assertThat(paymentObservation.path("toolCode").asText()).isEqualTo("payment_log_query");
        assertThat(List.of(orderObservation, paymentObservation)).allSatisfy(observation -> {
            assertThat(observation.path("type").asText()).isEqualTo("UNTRUSTED_TOOL_RESULT");
            assertThat(observation.path("reused").isBoolean()).isTrue();
            assertThat(observation.path("reused").booleanValue()).isFalse();
            assertThat(observation.path("data").path("status").asText()).isEqualTo("OK");
        });
        assertThat(secondPayload.path("availableTools")).isEqualTo(firstPayload.path("availableTools"));
        assertThat(thirdPayload.path("availableTools")).isEqualTo(firstPayload.path("availableTools"));
        LlmChatRequest finalRequest = requests.getAllValues().getLast();
        assertThat(finalRequest.messages().get(1).role()).isEqualTo(LlmMessageRole.SYSTEM);
        assertThat(finalRequest.messages().get(1).content())
                .contains("Generate only the user's final answer", "Do not output hidden chain-of-thought or action JSON")
                .doesNotContain("already completed successfully", "reused=true", "return FINISH for separate final generation");
        JsonNode finalPayload = mapper.readTree(finalRequest.messages().get(2).content());
        assertThat(finalPayload.path("observations")).isEqualTo(thirdPayload.path("observations"));
        assertThat(finalPayload.path("answerPlan").asText()).isEqualTo("Use existing evidence");
        assertThat(finalPayload.has("availableTools")).isFalse();
        assertThat(finalPayload.has("budget")).isFalse();
        assertThat(requests.getAllValues().getLast().maxOutputTokens()).isEqualTo(256);
        assertThat(requests.getAllValues().subList(0, 3)).allSatisfy(modelRequest ->
                assertThat(modelRequest.maxOutputTokens()).isEqualTo(512));
        assertThat(llmLogs).extracting(log -> log.requestSnapshot().path("maxOutputTokens").asInt())
                .containsExactly(512, 512, 512, 256);
        assertThat(llmLogs).allSatisfy(log -> assertThat(log.requestSnapshot().has("responseSchema")).isFalse());
        ArgumentCaptor<TaskEventRecord> events = ArgumentCaptor.forClass(TaskEventRecord.class);
        verify(recorder, atLeastOnce()).appendEvent(events.capture());
        assertThat(events.getAllValues()).extracting(TaskEventRecord::eventType).containsExactly(
                TaskEventType.RAG_FINISHED, TaskEventType.DECISION_FINISHED, TaskEventType.TOOL_STARTED,
                TaskEventType.TOOL_FINISHED, TaskEventType.DECISION_FINISHED, TaskEventType.TOOL_STARTED,
                TaskEventType.TOOL_FINISHED, TaskEventType.DECISION_FINISHED, TaskEventType.FINAL_GENERATION_STARTED);
        verify(lifecycle, never()).complete(anyLong(), any());
    }

    @Test
    void enabledDecisionSchemaUsesFrozenToolInputsAndLeavesIndependentFinalGenerationUnconstrained() throws Exception {
        TaskExecutionProperties properties = new TaskExecutionProperties();
        properties.setDecisionJsonSchemaEnabled(true);
        executor = executor(properties);
        properties.setDecisionJsonSchemaEnabled(false);
        JsonNode orderSchema = mapper.readTree("""
                {"type":"object","properties":{"orderNo":{"type":"string","maxLength":64}},
                 "required":["orderNo"],"additionalProperties":false}
                """);
        JsonNode paymentSchema = mapper.readTree("""
                {"type":"object","properties":{"orderNo":{"type":"string"},
                 "limit":{"type":"integer","minimum":1,"maximum":20}},
                 "required":["orderNo"],"additionalProperties":false}
                """);
        List<AgentTaskExecutionSnapshot.ToolSnapshot> frozenTools = List.of(
                new AgentTaskExecutionSnapshot.ToolSnapshot("11", "order_query", "Order", "Get order",
                        orderSchema, "hash-order", "builtin-v1", 1000),
                new AgentTaskExecutionSnapshot.ToolSnapshot("12", "payment_log_query", "Payment", "Get payment",
                        paymentSchema, "hash-payment", "builtin-v1", 1000));
        TaskExecutionRequest request = request(5, 3, 24000, 100000, frozenTools);
        when(rag.retrieve(any(), any())).thenReturn(evidence());
        script(call("order_query", "{\"orderNo\":\"A\"}"),
                call("payment_log_query", "{\"orderNo\":\"A\",\"limit\":5}"), finish(), "Answer [S1]");

        TaskExecutionOutcome outcome = executor.execute(request);

        assertThat(outcome.resultType()).isEqualTo(TaskExecutionResultType.COMPLETED);
        assertThat(outcome.terminationReason()).isEqualTo(TaskTerminationReason.ANSWERED);
        assertThat(outcome.decisionTurnsUsed()).isEqualTo(3);
        assertThat(outcome.toolCallsUsed()).isEqualTo(2);
        assertThat(outcome.citations().get(0).path("citationId").asText()).isEqualTo("S1");
        verify(tools, times(2)).execute(any());
        ArgumentCaptor<LlmChatRequest> requests = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(gateway, times(4)).chat(requests.capture());
        LlmResponseSchema responseSchema = requests.getAllValues().getFirst().responseSchema();
        assertThat(responseSchema).isNotNull();
        assertThat(responseSchema.name()).isEqualTo("agent_decision_v1");
        assertThat(responseSchema.schema().path("type").asText()).isEqualTo("object");
        JsonNode branches = responseSchema.schema().path("oneOf");
        assertThat(branches).hasSize(3);
        for (int index = 0; index < frozenTools.size(); index++) {
            JsonNode branch = branches.get(index);
            assertThat(branch.path("properties").size()).isEqualTo(4);
            assertThat(branch.path("properties").path("type").path("const").asText()).isEqualTo("CALL_TOOL");
            assertThat(branch.path("properties").path("toolCode").path("const").asText())
                    .isEqualTo(frozenTools.get(index).toolCode());
            assertThat(branch.path("properties").path("arguments")).isEqualTo(frozenTools.get(index).inputSchema());
            assertThat(branch.path("required")).isEqualTo(mapper.valueToTree(List.of("type", "toolCode", "arguments", "reason")));
            assertThat(branch.path("additionalProperties").asBoolean(true)).isFalse();
        }
        JsonNode finishBranch = branches.get(2);
        assertThat(finishBranch.path("properties").size()).isEqualTo(2);
        assertThat(finishBranch.path("properties").path("type").path("const").asText()).isEqualTo("FINISH");
        assertThat(finishBranch.path("required")).isEqualTo(mapper.valueToTree(List.of("type", "answerPlan")));
        assertThat(finishBranch.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(requests.getAllValues().subList(0, 3)).allSatisfy(modelRequest ->
                assertThat(modelRequest.responseSchema()).isEqualTo(responseSchema));
        assertThat(requests.getAllValues().getLast().responseSchema()).isNull();
        assertThat(requests.getAllValues().getLast().maxOutputTokens()).isEqualTo(request.finalTokenReserve());
        assertThat(llmLogs).extracting(LlmCallRecord::callType).containsExactly(
                LlmCallType.DECISION, LlmCallType.DECISION, LlmCallType.DECISION, LlmCallType.FINAL_GENERATION);
        assertThat(llmLogs.subList(0, 3)).allSatisfy(log ->
                assertThat(log.requestSnapshot().path("responseSchema")).isEqualTo(mapper.valueToTree(responseSchema)));
        assertThat(llmLogs.getLast().requestSnapshot().has("responseSchema")).isFalse();
    }

    @Test
    void jsonObjectDecisionsAndDisabledThinkingUseConfiguredCapsButKeepFinalGenerationAsText() {
        TaskExecutionProperties properties = new TaskExecutionProperties();
        properties.setDecisionJsonObjectEnabled(true);
        properties.setProviderThinkingDisabled(true);
        properties.setDecisionMaxOutputTokens(8192);
        properties.setFinalMaxOutputTokens(8192);
        executor = executor(properties);
        properties.setDecisionJsonObjectEnabled(false);
        properties.setProviderThinkingDisabled(false);
        properties.setDecisionMaxOutputTokens(512);
        properties.setFinalMaxOutputTokens(1);
        when(rag.retrieve(any(), any())).thenReturn(evidence());
        script(call("order_query", "{\"orderNo\":\"A\"}"),
                call("payment_log_query", "{}"), finish(), "Answer [S1]");
        TaskExecutionRequest request = request(5, 3, 24000);

        TaskExecutionOutcome outcome = executor.execute(request);

        assertThat(outcome.resultType()).isEqualTo(TaskExecutionResultType.COMPLETED);
        assertThat(outcome.terminationReason()).isEqualTo(TaskTerminationReason.ANSWERED);
        assertThat(outcome.toolCallsUsed()).isEqualTo(2);
        assertThat(request.finalTokenReserve()).isEqualTo(256);
        verify(tools, times(2)).execute(any());
        ArgumentCaptor<LlmChatRequest> requests = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(gateway, times(4)).chat(requests.capture());
        assertThat(requests.getAllValues()).allSatisfy(modelRequest -> {
            assertThat(modelRequest.maxOutputTokens()).isEqualTo(8192);
            assertThat(modelRequest.thinkingMode()).isEqualTo("disabled");
            assertThat(modelRequest.responseSchema()).isNull();
        });
        assertThat(requests.getAllValues().subList(0, 3)).allSatisfy(modelRequest ->
                assertThat(modelRequest.responseFormat()).isEqualTo("json_object"));
        assertThat(requests.getAllValues().getLast().responseFormat()).isNull();
        assertThat(llmLogs).extracting(LlmCallRecord::callType).containsExactly(
                LlmCallType.DECISION, LlmCallType.DECISION, LlmCallType.DECISION, LlmCallType.FINAL_GENERATION);
        assertThat(llmLogs).allSatisfy(log -> {
            assertThat(log.requestSnapshot().path("thinkingMode").asText()).isEqualTo("disabled");
            assertThat(log.requestSnapshot().path("maxOutputTokens").asInt()).isEqualTo(8192);
            assertThat(log.requestSnapshot().has("responseSchema")).isFalse();
        });
        assertThat(llmLogs.subList(0, 3)).allSatisfy(log ->
                assertThat(log.requestSnapshot().path("responseFormat").asText()).isEqualTo("json_object"));
        assertThat(llmLogs.getLast().requestSnapshot().has("responseFormat")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {512, 255})
    void optionalFinalCapUsesRemainingBudgetButNeverDropsBelowTheFrozenReserve(int remainingForFinalOutput) {
        TaskExecutionProperties properties = new TaskExecutionProperties();
        properties.setFinalMaxOutputTokens(8192);
        executor = executor(properties);
        TaskExecutionRequest request = request(3, 2, 24000);
        SnapshotRagResult empty = new SnapshotRagResult("", List.of(), 0, 0, "NONE");
        int finalInput = TaskTokenEstimator.inputTokens(new TaskPromptBuilder(mapper)
                .finalAnswer(request, empty, List.of(), "Use existing evidence"));
        int usedByDecision = 24000 - finalInput - remainingForFinalOutput;
        when(gateway.chat(any())).thenReturn(new LlmChatResult(finish(), "frozen-model", "stop",
                        LlmTokenUsage.known(usedByDecision - 3, 3, usedByDecision), "decision", 5),
                result("Useful final answer"));

        TaskExecutionOutcome outcome = executor.execute(request);

        if (remainingForFinalOutput >= request.finalTokenReserve()) {
            assertThat(outcome.resultType()).isEqualTo(TaskExecutionResultType.COMPLETED);
            ArgumentCaptor<LlmChatRequest> requests = ArgumentCaptor.forClass(LlmChatRequest.class);
            verify(gateway, times(2)).chat(requests.capture());
            assertThat(requests.getAllValues().getLast().maxOutputTokens()).isEqualTo(remainingForFinalOutput);
            assertThat(llmLogs.getLast().requestSnapshot().path("maxOutputTokens").asInt())
                    .isEqualTo(remainingForFinalOutput);
        } else {
            assertThat(outcome.terminationReason()).isEqualTo(TaskTerminationReason.TOKEN_BUDGET_EXHAUSTED);
            verify(gateway).chat(any());
            assertThat(llmLogs).singleElement().satisfies(log ->
                    assertThat(log.callType()).isEqualTo(LlmCallType.DECISION));
        }
        assertThat(request.finalTokenReserve()).isEqualTo(256);
    }

    @Test
    void optionalFinalCapIsAlsoConstrainedByTheFrozenContextWindow() {
        TaskExecutionProperties properties = new TaskExecutionProperties();
        properties.setDecisionMaxOutputTokens(8192);
        properties.setFinalMaxOutputTokens(8192);
        executor = executor(properties);
        TaskExecutionRequest reference = request(3, 2, 24000);
        SnapshotRagResult empty = new SnapshotRagResult("", List.of(), 0, 0, "NONE");
        TaskPromptBuilder prompts = new TaskPromptBuilder(mapper);
        int decisionInput = TaskTokenEstimator.inputTokens(prompts.decision(reference, empty, List.of(), 3, 2));
        int finalInput = TaskTokenEstimator.inputTokens(
                prompts.finalAnswer(reference, empty, List.of(), "Use existing evidence"));
        int contextWindow = Math.max(decisionInput + 128, finalInput + reference.finalTokenReserve());
        TaskExecutionRequest request = request(3, 2, 24000, contextWindow);
        script(finish(), "Useful final answer");

        TaskExecutionOutcome outcome = executor.execute(request);

        assertThat(outcome.resultType()).isEqualTo(TaskExecutionResultType.COMPLETED);
        ArgumentCaptor<LlmChatRequest> requests = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(gateway, times(2)).chat(requests.capture());
        int actualFinalCap = requests.getAllValues().getLast().maxOutputTokens();
        assertThat(actualFinalCap).isEqualTo(contextWindow - finalInput).isLessThan(8192);
        assertThat(actualFinalCap).isGreaterThanOrEqualTo(request.finalTokenReserve());
        assertThat(requests.getAllValues()).allSatisfy(modelRequest ->
                assertThat(TaskTokenEstimator.inputTokens(modelRequest.messages()) + modelRequest.maxOutputTokens())
                        .isLessThanOrEqualTo(contextWindow));
    }

    @Test
    void optionalFinalCapCannotReduceThePersistedReserve() {
        TaskExecutionProperties properties = new TaskExecutionProperties();
        properties.setFinalMaxOutputTokens(1);
        executor = executor(properties);
        TaskExecutionRequest request = request(3, 2, 24000);
        script(finish(), "Useful final answer");

        assertThat(executor.execute(request).resultType()).isEqualTo(TaskExecutionResultType.COMPLETED);

        ArgumentCaptor<LlmChatRequest> requests = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(gateway, times(2)).chat(requests.capture());
        assertThat(requests.getAllValues().getLast().maxOutputTokens()).isEqualTo(request.finalTokenReserve());
    }

    @Test
    void jsonObjectModeDoesNotRepairFencedDecisionsOrRetryTheProvider() {
        TaskExecutionProperties properties = new TaskExecutionProperties();
        properties.setDecisionJsonObjectEnabled(true);
        executor = executor(properties);
        script("```json\n" + finish() + "\n```");

        TaskExecutionOutcome outcome = executor.execute(request(3, 2, 24000));

        assertThat(outcome.errorCode()).isEqualTo("AGENT_INVALID_DECISION");
        assertThat(outcome.finalAnswer()).isNull();
        verify(gateway).chat(any());
        verifyNoInteractions(tools);
        assertThat(llmLogs).singleElement().satisfies(log -> {
            assertThat(log.status()).isEqualTo(TraceRecordStatus.FAILED);
            assertThat(log.responseText()).isNull();
        });
    }

    @Test
    void configuredDecisionCapIsFrozenAndRecordedWithoutChangingTheFinalReserve() {
        int decisionCap = 2048;
        TaskExecutionProperties properties = new TaskExecutionProperties();
        properties.setDecisionMaxOutputTokens(decisionCap);
        executor = executor(properties);
        properties.setDecisionMaxOutputTokens(4096);
        script(finish(), "Useful final answer");

        TaskExecutionRequest request = request(3, 2, 24000);
        TaskExecutionOutcome outcome = executor.execute(request);

        assertThat(outcome.resultType()).isEqualTo(TaskExecutionResultType.COMPLETED);
        assertThat(outcome.terminationReason()).isEqualTo(TaskTerminationReason.ANSWERED);
        ArgumentCaptor<LlmChatRequest> requests = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(gateway, times(2)).chat(requests.capture());
        assertThat(requests.getAllValues()).extracting(LlmChatRequest::maxOutputTokens)
                .containsExactly(decisionCap, request.finalTokenReserve());
        assertThat(llmLogs).extracting(log -> log.requestSnapshot().path("maxOutputTokens").asInt())
                .containsExactly(decisionCap, request.finalTokenReserve());
        assertThat(llmLogs).extracting(LlmCallRecord::callType)
                .containsExactly(LlmCallType.DECISION, LlmCallType.FINAL_GENERATION);
        assertThat(request.finalTokenReserve()).isEqualTo(256);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void increasedDecisionCapStillReservesTheFinalPromptAndOutputUnderALowTotalBudget(boolean schemaEnabled) {
        TaskExecutionProperties properties = new TaskExecutionProperties();
        properties.setDecisionMaxOutputTokens(2048);
        properties.setDecisionJsonSchemaEnabled(schemaEnabled);
        executor = executor(properties);
        TaskExecutionRequest reference = request(3, 2, 24000);
        TaskPromptBuilder prompts = new TaskPromptBuilder(mapper);
        SnapshotRagResult empty = new SnapshotRagResult("", List.of(), 0, 0, "NONE");
        int decisionInput = TaskTokenEstimator.inputTokens(prompts.decision(reference, empty, List.of(), 3, 2))
                + decisionSchemaTokens(reference, schemaEnabled);
        int finalInput = TaskTokenEstimator.inputTokens(
                prompts.finalAnswer(reference, empty, List.of(), "Use existing evidence"));
        int totalBudget = decisionInput + finalInput + reference.finalTokenReserve() + 512;
        TaskExecutionRequest request = request(3, 2, totalBudget);
        script(finish(), "Useful final answer");

        TaskExecutionOutcome outcome = executor.execute(request);

        assertThat(outcome.resultType()).isEqualTo(TaskExecutionResultType.COMPLETED);
        ArgumentCaptor<LlmChatRequest> requests = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(gateway, times(2)).chat(requests.capture());
        int actualDecisionCap = requests.getAllValues().getFirst().maxOutputTokens();
        assertThat(actualDecisionCap).isBetween(1, 1023);
        assertThat(actualDecisionCap + decisionInput + finalInput + request.finalTokenReserve())
                .isLessThanOrEqualTo(totalBudget);
        assertThat(requests.getAllValues().getLast().maxOutputTokens()).isEqualTo(request.finalTokenReserve());
        assertThat(llmLogs).extracting(log -> log.requestSnapshot().path("maxOutputTokens").asInt())
                .containsExactly(actualDecisionCap, request.finalTokenReserve());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void increasedDecisionCapStillFitsTheFrozenContextWindowWithoutReducingTheFinalReserve(boolean schemaEnabled) {
        TaskExecutionProperties properties = new TaskExecutionProperties();
        properties.setDecisionMaxOutputTokens(2048);
        properties.setDecisionJsonSchemaEnabled(schemaEnabled);
        executor = executor(properties);
        TaskExecutionRequest reference = request(3, 2, 24000);
        SnapshotRagResult empty = new SnapshotRagResult("", List.of(), 0, 0, "NONE");
        int decisionInput = TaskTokenEstimator.inputTokens(
                new TaskPromptBuilder(mapper).decision(reference, empty, List.of(), 3, 2))
                + decisionSchemaTokens(reference, schemaEnabled);
        TaskExecutionRequest request = request(3, 2, 24000, decisionInput + 128);
        script(finish(), "Useful final answer");

        TaskExecutionOutcome outcome = executor.execute(request);

        assertThat(outcome.resultType()).isEqualTo(TaskExecutionResultType.COMPLETED);
        ArgumentCaptor<LlmChatRequest> requests = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(gateway, times(2)).chat(requests.capture());
        assertThat(requests.getAllValues()).extracting(LlmChatRequest::maxOutputTokens)
                .containsExactly(128, request.finalTokenReserve());
        assertThat(requests.getAllValues()).allSatisfy(modelRequest ->
                assertThat(TaskTokenEstimator.inputTokens(modelRequest.messages())
                        + (modelRequest.responseSchema() == null ? 0
                        : TaskTokenEstimator.textTokens(mapper.valueToTree(modelRequest.responseSchema()).toString()))
                        + modelRequest.maxOutputTokens())
                        .isLessThanOrEqualTo(request.executionSnapshot().chatModel().contextWindow()));
        assertThat(llmLogs).extracting(log -> log.requestSnapshot().path("maxOutputTokens").asInt())
                .containsExactly(128, request.finalTokenReserve());
    }

    @Test
    void emptyRagStillAllowsToolsAndToolBudgetForcesARestrictedFinal() {
        script(call("order_query", "{}"), "Limited answer from tool facts");
        TaskExecutionOutcome outcome = executor.execute(request(3, 1, 50000));
        assertThat(outcome.resultType()).isEqualTo(TaskExecutionResultType.COMPLETED);
        assertThat(outcome.terminationReason()).isEqualTo(TaskTerminationReason.MAX_TOOL_CALLS);
        assertThat(outcome.decisionTurnsUsed()).isEqualTo(1);
        verify(tools).execute(any());
        assertThat(llmLogs.getLast().requestSnapshot().toString()).contains("BUDGET_LIMIT", "MAX_TOOL_CALLS");
    }

    @Test
    void decisionBudgetForcesFinalWithoutAnotherDecisionRequest() {
        script(call("order_query", "{}"), call("order_query", "{}"),
                call("payment_log_query", "{}"), call("payment_log_query", "{}"), "Limited answer");
        TaskExecutionOutcome outcome = executor.execute(request(4, 3, 50000));
        assertThat(outcome.resultType()).isEqualTo(TaskExecutionResultType.COMPLETED);
        assertThat(outcome.terminationReason()).isEqualTo(TaskTerminationReason.MAX_DECISION_TURNS);
        assertThat(outcome.decisionTurnsUsed()).isEqualTo(4);
        assertThat(outcome.toolCallsUsed()).isEqualTo(2);
        verify(gateway, times(5)).chat(any());
        verify(tools, times(2)).execute(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void canonicalDuplicateReusesSecondObservationAndRejectsThirdWithoutCallingRuntimeAgain(boolean jsonObjectEnabled) throws Exception {
        TaskExecutionProperties properties = new TaskExecutionProperties();
        properties.setDecisionJsonObjectEnabled(jsonObjectEnabled);
        executor = executor(properties);
        script(call("order_query", "{\"a\":1,\"b\":2}"), call("order_query", "{\"b\":2,\"a\":1}"),
                call("order_query", "{\"a\":1,\"b\":2}"));
        TaskExecutionOutcome outcome = executor.execute(request(5, 4, 50000));
        assertThat(outcome.errorCode()).isEqualTo("AGENT_DUPLICATE_TOOL_LOOP");
        assertThat(outcome.decisionTurnsUsed()).isEqualTo(3);
        assertThat(outcome.toolCallsUsed()).isEqualTo(1);
        assertThat(outcome.finalAnswer()).isNull();
        verify(tools).execute(any());
        ArgumentCaptor<LlmChatRequest> requests = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(gateway, times(3)).chat(requests.capture());
        JsonNode secondHistory = mapper.readTree(requests.getAllValues().get(1).messages().get(2).content()).path("observations");
        JsonNode thirdHistory = mapper.readTree(requests.getAllValues().get(2).messages().get(2).content()).path("observations");
        assertThat(secondHistory.size()).isEqualTo(1);
        assertThat(thirdHistory.size()).isEqualTo(2);
        assertThat(thirdHistory.get(0)).isEqualTo(secondHistory.get(0));
        assertThat(secondHistory.get(0).path("reused").isBoolean()).isTrue();
        assertThat(secondHistory.get(0).path("reused").booleanValue()).isFalse();
        assertThat(thirdHistory.get(1).path("reused").isBoolean()).isTrue();
        assertThat(thirdHistory.get(1).path("reused").booleanValue()).isTrue();
        assertThat(thirdHistory.get(1).path("type").asText()).isEqualTo("UNTRUSTED_TOOL_RESULT");
        assertThat(thirdHistory.get(1).path("toolCode").asText()).isEqualTo("order_query");
        assertThat(thirdHistory.get(1).path("data")).isEqualTo(secondHistory.get(0).path("data"));
        assertThat(thirdHistory.get(1).path("summary")).isEqualTo(secondHistory.get(0).path("summary"));
        assertThat(llmLogs).extracting(LlmCallRecord::callType)
                .containsExactly(LlmCallType.DECISION, LlmCallType.DECISION, LlmCallType.DECISION);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unknownUsageIsConservativelyEstimatedInTaskAndEachCallLog(boolean schemaEnabled) {
        TaskExecutionProperties properties = new TaskExecutionProperties();
        properties.setDecisionJsonSchemaEnabled(schemaEnabled);
        executor = executor(properties);
        when(gateway.chat(any())).thenReturn(new LlmChatResult(finish(), "frozen-model", "stop",
                LlmTokenUsage.unknown(), "request-1", 12),
                new LlmChatResult("Useful answer", "frozen-model", "stop", LlmTokenUsage.unknown(), "request-2", 14));
        TaskExecutionOutcome outcome = executor.execute(request(3, 2, 50000));
        assertThat(outcome.resultType()).isEqualTo(TaskExecutionResultType.COMPLETED);
        assertThat(outcome.tokenUsage().quality()).isEqualTo(TokenUsageQuality.ESTIMATED);
        assertThat(outcome.tokenUsage().totalTokens()).isGreaterThan(100);
        assertThat(llmLogs).allSatisfy(log -> {
            assertThat(log.usageQuality()).isEqualTo(TokenUsageQuality.ESTIMATED);
            assertThat(log.inputTokens()).isPositive();
            assertThat(log.outputTokens()).isPositive();
        });
        ArgumentCaptor<LlmChatRequest> requests = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(gateway, times(2)).chat(requests.capture());
        LlmChatRequest decision = requests.getAllValues().getFirst();
        int schemaInput = decision.responseSchema() == null ? 0
                : TaskTokenEstimator.textTokens(mapper.valueToTree(decision.responseSchema()).toString());
        assertThat(llmLogs.getFirst().inputTokens())
                .isEqualTo(TaskTokenEstimator.inputTokens(decision.messages()) + schemaInput);
        assertThat(schemaInput > 0).isEqualTo(schemaEnabled);
        assertThat(llmLogs.getLast().inputTokens())
                .isEqualTo(TaskTokenEstimator.inputTokens(requests.getAllValues().getLast().messages()));
    }

    @Test
    void malformedDecisionPersistsSafeFailedLogWithUsageAndLatencyWithoutRawResponse() {
        when(gateway.chat(any())).thenReturn(new LlmChatResult("invalid private raw response", "frozen-model", "stop",
                LlmTokenUsage.known(17, 5, 22), "request-x", 321));
        TaskExecutionOutcome outcome = executor.execute(request(3, 2, 50000));
        assertThat(outcome.errorCode()).isEqualTo("AGENT_INVALID_DECISION");
        assertThat(outcome.tokenUsage().totalTokens()).isEqualTo(22);
        assertThat(llmLogs).singleElement().satisfies(log -> {
            assertThat(log.status()).isEqualTo(TraceRecordStatus.FAILED);
            assertThat(log.responseText()).isNull();
            assertThat(log.totalTokens()).isEqualTo(22);
            assertThat(log.latencyMs()).isEqualTo(321);
            assertThat(log.errorCode()).isEqualTo("AGENT_INVALID_DECISION");
        });
        verifyNoInteractions(tools);
    }

    @Test
    void actualOverBudgetUsageIsRetainedBeforeFailure() {
        when(gateway.chat(any())).thenReturn(new LlmChatResult(finish(), "frozen-model", "stop",
                LlmTokenUsage.known(49000, 2000, 51000), "request-x", 20));
        TaskExecutionOutcome outcome = executor.execute(request(3, 2, 50000));
        assertThat(outcome.terminationReason()).isEqualTo(TaskTerminationReason.TOKEN_BUDGET_EXHAUSTED);
        assertThat(outcome.tokenUsage().totalTokens()).isEqualTo(51000);
        assertThat(llmLogs).singleElement().satisfies(log -> assertThat(log.totalTokens()).isEqualTo(51000));
        verify(gateway).chat(any());
    }

    @Test
    void insufficientBudgetBlocksProviderBeforeFirstDecision() {
        TaskExecutionOutcome outcome = executor.execute(request(3, 2, 400));
        assertThat(outcome.terminationReason()).isEqualTo(TaskTerminationReason.TOKEN_BUDGET_EXHAUSTED);
        assertThat(outcome.decisionTurnsUsed()).isZero();
        verifyNoInteractions(gateway, tools);
    }

    @Test
    void cancellationAfterProviderResponsePreservesUsageAndStopsFurtherIo() {
        when(gateway.chat(any())).thenAnswer(call -> {
            cancelled.set(true);
            return result(finish());
        });
        TaskExecutionOutcome outcome = executor.execute(request(3, 2, 50000));
        assertThat(outcome.resultType()).isEqualTo(TaskExecutionResultType.CANCELLED);
        assertThat(outcome.tokenUsage().totalTokens()).isEqualTo(10);
        verify(gateway).chat(any());
        verifyNoInteractions(tools);
    }

    @Test
    void deadlineAfterProviderResponsePreservesUsageAndStopsFurtherIo() {
        when(gateway.chat(any())).thenAnswer(call -> {
            now.set(now.get().plusSeconds(61));
            return result(finish());
        });
        TaskExecutionOutcome outcome = executor.execute(request(3, 2, 50000));
        assertThat(outcome.resultType()).isEqualTo(TaskExecutionResultType.TIMED_OUT);
        assertThat(outcome.tokenUsage().totalTokens()).isEqualTo(10);
        verify(gateway).chat(any());
    }

    @Test
    void fabricatedFinalCitationFailsAfterRecordingFinalUsage() {
        when(rag.retrieve(any(), any())).thenReturn(evidence());
        script(finish(), "Made up evidence [S999]");
        TaskExecutionOutcome outcome = executor.execute(request(3, 2, 50000));
        assertThat(outcome.errorCode()).isEqualTo("AGENT_INVALID_CITATION");
        assertThat(outcome.tokenUsage().totalTokens()).isEqualTo(20);
        assertThat(outcome.finalAnswer()).isNull();
        assertThat(outcome.citations()).isEmpty();
        assertThat(llmLogs.getLast().status()).isEqualTo(TraceRecordStatus.FAILED);
        assertThat(llmLogs.getLast().responseText()).isNull();
    }

    @Test
    void unsupportedFrozenProtocolFailsBeforeAnyExternalCall() {
        TaskExecutionRequest original = request(3, 2, 50000);
        var value = original.executionSnapshot();
        var unsupported = new AgentTaskExecutionSnapshot(value.snapshotVersion(), value.agent(),
                new AgentTaskExecutionSnapshot.RuntimeSnapshot("unsupported-v2", "agent-runtime-rules-v1", "frozen-revision"),
                value.chatModel(), value.retrieval(), value.tools());
        var changed = new TaskExecutionRequest(101, 7, 8, "Investigate", unsupported, 256,
                original.deadlineAt(), cancelled::get);
        assertThat(executor.execute(changed).errorCode()).isEqualTo("AGENT_INVALID_SNAPSHOT");
        verifyNoInteractions(gateway, tools, rag);
    }

    @Test
    void blockedProviderCancellationReturnsWithEstimatedUsageAndNoNextAction() throws Exception {
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        when(gateway.chat(any())).thenAnswer(call -> {
            entered.countDown();
            new java.util.concurrent.CountDownLatch(1).await();
            return result(finish());
        });
        Thread canceller = Thread.ofVirtual().start(() -> {
            try {
                if (entered.await(2, java.util.concurrent.TimeUnit.SECONDS)) cancelled.set(true);
            } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        });
        TaskExecutionOutcome outcome = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(3), () -> executor.execute(request(3, 2, 50000)));
        canceller.join(1000);
        assertThat(outcome.resultType()).isEqualTo(TaskExecutionResultType.CANCELLED);
        assertThat(outcome.decisionTurnsUsed()).isEqualTo(1);
        assertThat(outcome.tokenUsage().quality()).isEqualTo(TokenUsageQuality.ESTIMATED);
        assertThat(outcome.tokenUsage().totalTokens()).isPositive();
        verifyNoInteractions(tools);
    }

    @Test
    void toolRevocationAlsoBlocksReuseOfACachedObservation() {
        script(call("order_query", "{}"), call("order_query", "{}"));
        doThrow(new ToolTaskExecutionException("TOOL_SNAPSHOT_MISMATCH", "Tool unavailable"))
                .when(tools).validateTaskSnapshot(any());
        TaskExecutionOutcome outcome = executor.execute(request(5, 4, 50000));
        assertThat(outcome.errorCode()).isEqualTo("TOOL_SNAPSHOT_MISMATCH");
        assertThat(outcome.toolCallsUsed()).isEqualTo(1);
        verify(tools).execute(any());
    }

    @Test
    void malformedCitationBracketsCannotHideAnUnknownOrApparentlyValidInnerMarker() {
        when(rag.retrieve(any(), any())).thenReturn(evidence());
        for (String answer : List.of("Evidence [[S1]]", "Evidence [S999", "Evidence [C8]")) {
            script(finish(), answer);
            assertThat(executor.execute(request(3, 2, 50000)).errorCode()).isEqualTo("AGENT_INVALID_CITATION");
        }
    }

    @Test
    void traceWriteFailureRetainsMeasuredUsageAndFailsTheRunningStep() {
        script(finish());
        doThrow(new IllegalStateException("Trace persistence unavailable")).when(recorder).recordLlmCall(any());
        TaskExecutionOutcome outcome = executor.execute(request(3, 2, 50000));
        assertThat(outcome.resultType()).isEqualTo(TaskExecutionResultType.FAILED);
        assertThat(outcome.tokenUsage().totalTokens()).isEqualTo(10);
        assertThat(outcome.decisionTurnsUsed()).isEqualTo(1);
        verify(recorder).failStep(argThat(step -> step.stepType() == StepType.LLM_DECISION),
                eq("AGENT_EXECUTION_FAILED"), anyString());
    }

    @Test
    void v2SettingsOverrideChangedDeploymentDefaultsAndSurviveJsonRoundTrip() throws Exception {
        TaskExecutionProperties deployment = new TaskExecutionProperties();
        deployment.setDecisionMaxOutputTokens(19);
        deployment.setFinalMaxOutputTokens(300);
        deployment.setDecisionJsonSchemaEnabled(true);
        executor = executor(deployment);
        script(finish(), "Answer");
        var requested = advancedRequest(2048, 3072, "JSON_OBJECT", "DISABLED", 120);
        var frozen = mapper.readValue(mapper.writeValueAsString(requested.executionSnapshot()), AgentTaskExecutionSnapshot.class);
        var request = new TaskExecutionRequest(101, 7, 8, "Investigate", frozen, 256,
                now.get().plusSeconds(240), cancelled::get);
        assertThat(executor.execute(request).resultType()).isEqualTo(TaskExecutionResultType.COMPLETED);
        ArgumentCaptor<LlmChatRequest> captures = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(gateway, times(2)).chat(captures.capture());
        var decision = captures.getAllValues().getFirst();
        var answer = captures.getAllValues().getLast();
        assertThat(decision.maxOutputTokens()).isEqualTo(2048);
        assertThat(decision.responseFormat()).isEqualTo("json_object");
        assertThat(decision.responseSchema()).isNull();
        assertThat(answer.maxOutputTokens()).isEqualTo(3072);
        assertThat(answer.responseFormat()).isNull();
        assertThat(answer.responseSchema()).isNull();
        assertThat(captures.getAllValues()).allSatisfy(value -> {
            assertThat(value.thinkingMode()).isEqualTo("disabled");
            assertThat(value.timeoutSeconds()).isEqualTo(120);
        });
        assertThat(llmLogs.getFirst().requestSnapshot().path("timeoutSeconds").asInt()).isEqualTo(120);
    }

    @Test
    void recordsReturnedUsageAndFinishReasonForOutputLimitWithoutPublishingRawResponse() {
        when(gateway.chat(any())).thenThrow(new LlmGatewayException(LlmFailureType.OUTPUT_LIMIT,
                "Output was truncated", new LlmFailureMetadata("frozen-model", "length",
                LlmTokenUsage.known(784, 511, 1295), "request-limit", 29000)));
        var outcome = executor.execute(advancedRequest(512, 2048, "PROMPT_ONLY", "PROVIDER_DEFAULT", 60));
        assertThat(outcome.errorCode()).isEqualTo("AGENT_LLM_OUTPUT_LIMIT");
        assertThat(outcome.tokenUsage().totalTokens()).isEqualTo(1295);
        assertThat(outcome.tokenUsage().quality()).isEqualTo(TokenUsageQuality.EXACT);
        assertThat(llmLogs).singleElement().satisfies(value -> {
            assertThat(value.finishReason()).isEqualTo("length");
            assertThat(value.responseText()).isNull();
            assertThat(value.providerRequestId()).isEqualTo("request-limit");
            assertThat(value.latencyMs()).isEqualTo(29000);
        });
        verifyNoInteractions(tools);
        verify(gateway).chat(any());
    }

    @Test
    void boundedV2CallTimeoutIsDifferentFromWholeTaskTimeout() {
        when(gateway.chat(any())).thenAnswer(call -> {
            new java.util.concurrent.CountDownLatch(1).await();
            return result(finish());
        });
        var outcome = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(3), () -> executor.execute(
                        advancedRequest(2048, 2048, "PROMPT_ONLY", "PROVIDER_DEFAULT", 1)));
        assertThat(outcome.resultType()).isEqualTo(TaskExecutionResultType.FAILED);
        assertThat(outcome.errorCode()).isEqualTo("AGENT_LLM_TIMEOUT");
        assertThat(outcome.tokenUsage().quality()).isEqualTo(TokenUsageQuality.ESTIMATED);
        verifyNoInteractions(tools);
    }

    @Test
    void responseAtSingleCallDeadlineRetainsUsageAndDoesNotStartFinalGeneration() {
        when(gateway.chat(any())).thenAnswer(call -> {
            now.set(now.get().plusSeconds(2));
            return result(finish());
        });
        var outcome = executor.execute(advancedRequest(2048, 2048, "PROMPT_ONLY", "PROVIDER_DEFAULT", 1));
        assertThat(outcome.resultType()).isEqualTo(TaskExecutionResultType.FAILED);
        assertThat(outcome.errorCode()).isEqualTo("AGENT_LLM_TIMEOUT");
        assertThat(outcome.tokenUsage().totalTokens()).isEqualTo(10);
        assertThat(outcome.tokenUsage().quality()).isEqualTo(TokenUsageQuality.EXACT);
        verify(gateway).chat(any());
        verifyNoInteractions(tools);
    }

    @Test
    void responseAtWholeTaskDeadlineKeepsTaskTimeoutWhenModelLimitIsLater() {
        when(gateway.chat(any())).thenAnswer(call -> {
            now.set(now.get().plusSeconds(61));
            return result(finish());
        });
        var outcome = executor.execute(advancedRequest(2048, 2048, "PROMPT_ONLY", "PROVIDER_DEFAULT", 120));
        assertThat(outcome.resultType()).isEqualTo(TaskExecutionResultType.TIMED_OUT);
        assertThat(outcome.errorCode()).isNull();
        assertThat(outcome.tokenUsage().totalTokens()).isEqualTo(10);
        verify(gateway).chat(any());
        verifyNoInteractions(tools);
    }

    @Test
    void v2WithoutSettingsRejectsBeforeRetrievalAndV1RemainsReadable() throws Exception {
        var legacy = request(3, 2, 50000);
        var value = legacy.executionSnapshot();
        var invalid = new AgentTaskExecutionSnapshot("agent-task-snapshot-v2", value.agent(), value.runtime(),
                value.chatModel(), value.retrieval(), value.tools());
        var request = new TaskExecutionRequest(101, 7, 8, "Investigate", invalid, 256,
                legacy.deadlineAt(), cancelled::get);
        assertThat(executor.execute(request).errorCode()).isEqualTo("AGENT_INVALID_SNAPSHOT");
        verifyNoInteractions(gateway, tools, rag);
        var reread = mapper.readValue(mapper.writeValueAsString(value), AgentTaskExecutionSnapshot.class);
        assertThat(reread.snapshotVersion()).isEqualTo("agent-task-snapshot-v1");
        assertThat(reread.executionSettings()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"agent-runtime-rules-v1", "agent-runtime-rules-v2"})
    void missingLookupIdentifiersCanFinishWithASeparateClarificationWithoutCallingTools(String rulesVersion) throws Exception {
        String plan = "Explain that no order number or payment error code was supplied and request it.";
        String answer = "请提供订单号或支付错误码；目前无法确认这笔订单的支付失败原因。";
        script(mapper.createObjectNode().put("type", "FINISH").put("answerPlan", plan).toString(), answer);

        var outcome = executor.execute(missingIdentifierRequest(rulesVersion));

        assertThat(outcome.resultType()).isEqualTo(TaskExecutionResultType.COMPLETED);
        assertThat(outcome.finalAnswer()).isEqualTo(answer);
        assertThat(outcome.decisionTurnsUsed()).isEqualTo(1);
        assertThat(outcome.toolCallsUsed()).isZero();
        verifyNoInteractions(tools);
        ArgumentCaptor<LlmChatRequest> requests = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(gateway, times(2)).chat(requests.capture());
        assertThat(llmLogs).extracting(LlmCallRecord::callType)
                .containsExactly(LlmCallType.DECISION, LlmCallType.FINAL_GENERATION);
        var decision = requests.getAllValues().getFirst();
        var finalRequest = requests.getAllValues().getLast();
        assertThat(mapper.readTree(finalRequest.messages().get(2).content()).path("answerPlan").asText())
                .isEqualTo(plan);
        if (TaskPromptBuilder.CURRENT_RULES_VERSION.equals(rulesVersion)) {
            assertThat(decision.messages().get(1).content()).contains(
                    "FINISH ends tool planning", "Never guess identifiers", "A FINISH clarification plan is a valid decision");
            assertThat(finalRequest.messages().get(1).content()).contains("do not claim it is waiting");
        } else {
            assertThat(decision.messages().get(1).content()).doesNotContain("FINISH ends tool planning");
            assertThat(finalRequest.messages().get(1).content()).doesNotContain("do not claim it is waiting");
        }
    }

    @Test
    void plainTextClarificationWithNormalStopIsStillAnInvalidDecisionAndIsNotRetried() {
        when(gateway.chat(any())).thenReturn(new LlmChatResult(
                "I need an order number or payment error code to analyze the failure reason. Please provide one of these identifiers.",
                "google/gemma-4-e4b", "stop", LlmTokenUsage.known(784, 2054, 2838), "request", 103000));

        var outcome = executor.execute(missingIdentifierRequest(TaskPromptBuilder.CURRENT_RULES_VERSION));

        assertThat(outcome.resultType()).isEqualTo(TaskExecutionResultType.FAILED);
        assertThat(outcome.errorCode()).isEqualTo("AGENT_INVALID_DECISION");
        assertThat(outcome.tokenUsage().totalTokens()).isEqualTo(2838);
        assertThat(outcome.toolCallsUsed()).isZero();
        verify(gateway, times(1)).chat(any());
        verifyNoInteractions(tools);
        assertThat(llmLogs).extracting(LlmCallRecord::callType).containsExactly(LlmCallType.DECISION);
    }

    private TaskExecutionRequest missingIdentifierRequest(String rulesVersion) {
        var base = advancedRequest(4096, 2048, "PROMPT_ONLY", "PROVIDER_DEFAULT", 120);
        var value = base.executionSnapshot();
        var runtime = new AgentTaskExecutionSnapshot.RuntimeSnapshot("agent-decision-json-v1", rulesVersion, "frozen-revision");
        var orderSchema = mapper.createObjectNode().put("type", "object");
        orderSchema.putObject("properties").putObject("orderNo").put("type", "string");
        orderSchema.putArray("required").add("orderNo");
        var paymentSchema = orderSchema.deepCopy();
        paymentSchema.withObject("properties").putObject("errorCode").put("type", "string");
        paymentSchema.remove("required");
        paymentSchema.putArray("anyOf").addObject().putArray("required").add("orderNo");
        paymentSchema.withArray("anyOf").addObject().putArray("required").add("errorCode");
        var availableTools = List.of(
                new AgentTaskExecutionSnapshot.ToolSnapshot("11", "order_query", "Order", "Get order",
                        orderSchema, "hash-order", "builtin-v1", 1000),
                new AgentTaskExecutionSnapshot.ToolSnapshot("12", "payment_log_query", "Payment", "Get payment",
                        paymentSchema, "hash-payment", "builtin-v1", 1000));
        var snapshot = new AgentTaskExecutionSnapshot(value.snapshotVersion(), value.agent(), runtime,
                value.chatModel(), value.retrieval(), availableTools, value.executionSettings());
        return new TaskExecutionRequest(101, 7, 8, "分析订单支付失败原因", snapshot, 256, base.deadlineAt(), cancelled::get);
    }

    private TaskExecutionRequest advancedRequest(int decision, int answer, String format, String thinking, int timeout) {
        var base = request(3, 2, 50000);
        var value = base.executionSnapshot();
        var settings = new ResolvedAgentExecutionSettings("agent-execution-policy-v1", decision, answer, format,
                thinking, timeout, java.util.Map.of("decisionMaxOutputTokens", "AGENT_OVERRIDE"));
        var snapshot = new AgentTaskExecutionSnapshot("agent-task-snapshot-v2", value.agent(), value.runtime(),
                value.chatModel(), value.retrieval(), value.tools(), settings);
        return new TaskExecutionRequest(101, 7, 8, "Investigate", snapshot, 256, base.deadlineAt(), cancelled::get);
    }

    private TaskExecutionRequest request(int decisions, int toolCalls, int tokens) {
        return request(decisions, toolCalls, tokens, 100000);
    }

    private TaskExecutionRequest request(int decisions, int toolCalls, int tokens, int contextWindow) {
        var schema = mapper.createObjectNode().put("type", "object");
        return request(decisions, toolCalls, tokens, contextWindow,
                List.of(new AgentTaskExecutionSnapshot.ToolSnapshot("11", "order_query", "Order", "Get order",
                                schema, "hash-order", "builtin-v1", 1000),
                        new AgentTaskExecutionSnapshot.ToolSnapshot("12", "payment_log_query", "Payment", "Get payment",
                                schema, "hash-payment", "builtin-v1", 1000)));
    }

    private TaskExecutionRequest request(int decisions, int toolCalls, int tokens, int contextWindow,
            List<AgentTaskExecutionSnapshot.ToolSnapshot> frozenTools) {
        var snapshot = new AgentTaskExecutionSnapshot("agent-task-snapshot-v1",
                new AgentTaskExecutionSnapshot.AgentSnapshot("8", "Frozen task system prompt", "ACTIVE", decisions, toolCalls, tokens, 60),
                new AgentTaskExecutionSnapshot.RuntimeSnapshot("agent-decision-json-v1", "agent-runtime-rules-v1", "frozen-revision"),
                new AgentTaskExecutionSnapshot.ChatModelSnapshot("openai-compatible-default", "frozen-provider", "frozen-model",
                        BigDecimal.ZERO, BigDecimal.ONE, contextWindow, true),
                new AgentTaskExecutionSnapshot.RetrievalSnapshot(List.of(), 5, BigDecimal.ZERO, false),
                frozenTools);
        return new TaskExecutionRequest(101, 7, 8, "Investigate", snapshot, 256,
                now.get().plusSeconds(60), cancelled::get);
    }

    private int decisionSchemaTokens(TaskExecutionRequest request, boolean schemaEnabled) {
        return schemaEnabled ? TaskTokenEstimator.textTokens(mapper.valueToTree(
                AgentDecisionResponseSchema.fromTools(mapper, request.executionSnapshot().tools())).toString()) : 0;
    }

    private TaskSnapshotAgentExecutor executor(TaskExecutionProperties properties) {
        return new TaskSnapshotAgentExecutor(rag, gateway, tools, factory, lifecycle,
                new AgentDecisionParser(mapper), new TaskPromptBuilder(mapper), mapper, clock, properties);
    }

    private SnapshotRagResult evidence() {
        return new SnapshotRagResult("[S1] verified source", List.of(new RagRetrievalHitRecord(1, "S1", 31, 21, 11, 2,
                BigDecimal.ONE, "verified source", mapper.createObjectNode())), 1, 0, "embedding-v1");
    }
    private void script(String... contents) {
        ArrayDeque<String> values = new ArrayDeque<>(List.of(contents));
        doAnswer(call -> result(values.removeFirst())).when(gateway).chat(any());
    }
    private static LlmChatResult result(String content) {
        return new LlmChatResult(content, "frozen-model", "stop", LlmTokenUsage.known(7, 3, 10), "request", 5);
    }
    private static String finish() { return "{\"type\":\"FINISH\",\"answerPlan\":\"Use existing evidence\"}"; }
    private static String call(String toolCode, String arguments) {
        return "{\"type\":\"CALL_TOOL\",\"toolCode\":\"" + toolCode + "\",\"arguments\":" + arguments + ",\"reason\":\"Check facts\"}";
    }
}
