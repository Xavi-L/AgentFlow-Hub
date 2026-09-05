package com.agentflow.agent.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.agentflow.agent.rag.SnapshotRagResult;
import com.agentflow.agent.rag.SnapshotRagService;
import com.agentflow.agent.snapshot.AgentTaskExecutionSnapshot;
import com.agentflow.agent.task.execution.*;
import com.agentflow.agent.task.model.*;
import com.agentflow.agent.task.service.AgentTaskLifecycleTransactionService;
import com.agentflow.agent.trace.*;
import com.agentflow.infra.llm.*;
import com.agentflow.tool.*;
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
        executor = new TaskSnapshotAgentExecutor(rag, gateway, tools, factory, lifecycle,
                new AgentDecisionParser(mapper), new TaskPromptBuilder(mapper), mapper, clock);
    }

    @Test
    void executesFrozenRagDecisionToolsAndSeparateFinalGenerationWithOrderedFacts() {
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
            assertThat(modelRequest.modelProvider()).isEqualTo("frozen-provider");
            assertThat(modelRequest.modelName()).isEqualTo("frozen-model");
            assertThat(modelRequest.messages().get(0).content()).isEqualTo("Frozen task system prompt");
            assertThat(modelRequest.messages().get(2).content()).contains("S1", "UNTRUSTED_DATA");
        });
        assertThat(requests.getAllValues().getLast().maxOutputTokens()).isEqualTo(256);
        ArgumentCaptor<TaskEventRecord> events = ArgumentCaptor.forClass(TaskEventRecord.class);
        verify(recorder, atLeastOnce()).appendEvent(events.capture());
        assertThat(events.getAllValues()).extracting(TaskEventRecord::eventType).containsExactly(
                TaskEventType.RAG_FINISHED, TaskEventType.DECISION_FINISHED, TaskEventType.TOOL_STARTED,
                TaskEventType.TOOL_FINISHED, TaskEventType.DECISION_FINISHED, TaskEventType.TOOL_STARTED,
                TaskEventType.TOOL_FINISHED, TaskEventType.DECISION_FINISHED, TaskEventType.FINAL_GENERATION_STARTED);
        verify(lifecycle, never()).complete(anyLong(), any());
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

    @Test
    void canonicalDuplicateReusesSecondObservationAndRejectsThirdWithoutCallingRuntimeAgain() {
        script(call("order_query", "{\"a\":1,\"b\":2}"), call("order_query", "{\"b\":2,\"a\":1}"),
                call("order_query", "{\"a\":1,\"b\":2}"));
        TaskExecutionOutcome outcome = executor.execute(request(5, 4, 50000));
        assertThat(outcome.errorCode()).isEqualTo("AGENT_DUPLICATE_TOOL_LOOP");
        assertThat(outcome.decisionTurnsUsed()).isEqualTo(3);
        assertThat(outcome.toolCallsUsed()).isEqualTo(1);
        verify(tools).execute(any());
        assertThat(llmLogs.getLast().requestSnapshot().toString()).contains("\\\"reused\\\":true");
    }

    @Test
    void unknownUsageIsConservativelyEstimatedInTaskAndEachCallLog() {
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

    private TaskExecutionRequest request(int decisions, int toolCalls, int tokens) {
        var schema = mapper.createObjectNode().put("type", "object");
        var snapshot = new AgentTaskExecutionSnapshot("agent-task-snapshot-v1",
                new AgentTaskExecutionSnapshot.AgentSnapshot("8", "Frozen task system prompt", "ACTIVE", decisions, toolCalls, tokens, 60),
                new AgentTaskExecutionSnapshot.RuntimeSnapshot("agent-decision-json-v1", "agent-runtime-rules-v1", "frozen-revision"),
                new AgentTaskExecutionSnapshot.ChatModelSnapshot("openai-compatible-default", "frozen-provider", "frozen-model",
                        BigDecimal.ZERO, BigDecimal.ONE, 100000, true),
                new AgentTaskExecutionSnapshot.RetrievalSnapshot(List.of(), 5, BigDecimal.ZERO, false),
                List.of(new AgentTaskExecutionSnapshot.ToolSnapshot("11", "order_query", "Order", "Get order",
                                schema, "hash-order", "builtin-v1", 1000),
                        new AgentTaskExecutionSnapshot.ToolSnapshot("12", "payment_log_query", "Payment", "Get payment",
                                schema, "hash-payment", "builtin-v1", 1000)));
        return new TaskExecutionRequest(101, 7, 8, "Investigate", snapshot, 256,
                now.get().plusSeconds(60), cancelled::get);
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
