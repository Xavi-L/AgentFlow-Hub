package com.agentflow.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agentflow.agent.snapshot.AgentTaskExecutionSnapshot;
import com.agentflow.agent.snapshot.AgentTaskExecutionSnapshot.AgentSnapshot;
import com.agentflow.agent.snapshot.AgentTaskExecutionSnapshot.ChatModelSnapshot;
import com.agentflow.agent.snapshot.AgentTaskExecutionSnapshot.RetrievalSnapshot;
import com.agentflow.agent.snapshot.AgentTaskExecutionSnapshot.RuntimeSnapshot;
import com.agentflow.agent.snapshot.AgentTaskExecutionSnapshot.ToolSnapshot;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class TaskScopedToolRuntimeTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper();
    private ToolDefinitionService definitions;
    private BuiltinToolExecutor executor;
    private ToolCallLogService logs;
    private DefaultToolRuntime runtime;
    private ToolDefinition current;
    private JsonNode arguments;

    @BeforeEach
    void setUp() throws Exception {
        definitions = Mockito.mock(ToolDefinitionService.class);
        executor = Mockito.mock(BuiltinToolExecutor.class);
        logs = Mockito.mock(ToolCallLogService.class);
        runtime = new DefaultToolRuntime(definitions, new ToolArgumentValidator(), executor, logs,
                Clock.fixed(NOW, ZoneOffset.UTC));
        current = definition(mapper.readTree("""
                {"type":"object","properties":{"orderNo":{"type":"string","minLength":1}},
                 "required":["orderNo"],"additionalProperties":false}
                """), "builtin-v1", 3_000);
        arguments = mapper.readTree("{\"orderNo\":\"order_1024\"}");
        when(definitions.findActiveById(11L)).thenReturn(Optional.of(current));
        when(logs.recordRunning(any(), any(), any())).thenReturn(100L);
    }

    @Test
    void shouldUseFrozenMetadataAndTimeoutWithoutReadingBindingsAndLogBeforeReturning() {
        ToolSnapshot frozen = frozen(current.inputSchema(), "builtin-v1", 400);
        when(executor.execute(any(), any())).thenReturn(new BuiltinToolHandler.HandlerResult(
                "safe result", mapper.createObjectNode().put("found", true)));

        ToolExecutionCommand command = command(frozen, NOW.plusSeconds(5), () -> { });
        ((com.fasterxml.jackson.databind.node.ObjectNode) arguments).put("orderNo", "mutated");
        ToolExecutionResult result = runtime.execute(command);

        ArgumentCaptor<ToolDefinition> executed = ArgumentCaptor.forClass(ToolDefinition.class);
        ArgumentCaptor<JsonNode> supplied = ArgumentCaptor.forClass(JsonNode.class);
        verify(executor).execute(executed.capture(), supplied.capture());
        assertThat(executed.getValue().name()).isEqualTo("Frozen tool name");
        assertThat(executed.getValue().timeoutMs()).isEqualTo(400);
        assertThat(supplied.getValue().path("orderNo").asText()).isEqualTo("order_1024");
        assertThat(result.success()).isTrue();
        var order = Mockito.inOrder(logs, executor);
        order.verify(logs).recordRunning(any(), eq(command), any());
        order.verify(executor).execute(any(), any());
        order.verify(logs).recordSuccess(100L, result);
        verify(definitions, never()).listActive();
    }

    @Test
    void shouldRevalidateCachedObservationWithoutAnActualCallOrCallLog() {
        ToolExecutionCommand command = command(frozen(current.inputSchema(), "builtin-v1", 500),
                NOW.plusSeconds(5), () -> { });
        runtime.validateTaskSnapshot(command);
        verifyNoInteractions(executor, logs);

        when(definitions.findActiveById(11L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> runtime.validateTaskSnapshot(command))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.TOOL_NOT_FOUND));
        verifyNoInteractions(executor, logs);
    }

    @Test
    void shouldRejectDisabledOrDeletedToolDespiteItsPresenceInSnapshot() {
        when(definitions.findActiveById(11L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> runtime.execute(command(frozen(current.inputSchema(), "builtin-v1", 500),
                NOW.plusSeconds(5), () -> { })))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.TOOL_NOT_FOUND));

        verify(logs).recordRejected(any(), any(), any(), any());
        verify(logs, never()).recordRunning(any(), any(), any());
        verifyNoInteractions(executor);
    }

    @Test
    void shouldRejectSchemaOrImplementationDriftBeforeTheHandler() throws Exception {
        JsonNode changedSchema = mapper.readTree("{\"type\":\"object\",\"properties\":{}}");
        assertSnapshotRejected(frozen(changedSchema, "builtin-v1", 500));
        assertSnapshotRejected(frozen(current.inputSchema(), "builtin-v2", 500));
        when(definitions.findActiveById(11L)).thenReturn(Optional.of(
                definition(current.inputSchema(), "builtin-v2", 3_000)));
        assertSnapshotRejected(frozen(current.inputSchema(), "builtin-v1", 500));
        verifyNoInteractions(executor);
    }

    @Test
    void shouldRejectForgedHashAndMissingAllowlistEntry() {
        ToolSnapshot forged = new ToolSnapshot("11", "order_query", "name", "description",
                current.inputSchema(), "forged", "builtin-v1", 500);
        assertSnapshotRejected(forged);
        AgentTaskExecutionSnapshot empty = snapshot(List.of());
        assertThatThrownBy(() -> runtime.execute(ToolExecutionCommand.taskScoped(
                11L, 21L, 31L, 1L, 2L, empty, arguments, NOW.plusSeconds(5), () -> { })))
                .isInstanceOf(ToolTaskExecutionException.class);
        verifyNoInteractions(executor);
    }

    @Test
    void shouldRejectMissingTaskSnapshotInsteadOfFallingBackToCurrentRegistry() {
        assertThatThrownBy(() -> runtime.execute(ToolExecutionCommand.taskScoped(11L, 21L, 31L, arguments)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("complete task snapshot");
        verifyNoInteractions(definitions, executor, logs);
    }

    @Test
    void shouldRejectExpiredDeadlineBeforeCallingHandler() {
        assertThatThrownBy(() -> runtime.execute(command(frozen(current.inputSchema(), "builtin-v1", 500),
                NOW, () -> { })))
                .isInstanceOfSatisfying(ToolTaskExecutionException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo("TOOL_TIMEOUT"));
        verify(logs).recordRejected(any(), any(), any(), any());
        verifyNoInteractions(executor);
    }

    @Test
    void shouldPreserveCancellationRaisedAfterHandlerAndFinishLogFirst() {
        AtomicBoolean cancelled = new AtomicBoolean();
        IllegalStateException cancellation = new IllegalStateException("cancelled");
        when(executor.execute(any(), any())).thenAnswer(invocation -> {
            cancelled.set(true);
            return new BuiltinToolHandler.HandlerResult("result", mapper.createObjectNode());
        });
        Runnable boundary = () -> {
            if (cancelled.get()) {
                throw cancellation;
            }
        };
        assertThatThrownBy(() -> runtime.execute(command(frozen(current.inputSchema(), "builtin-v1", 500),
                NOW.plusSeconds(5), boundary))).isSameAs(cancellation);
        ArgumentCaptor<ToolExecutionResult> failure = ArgumentCaptor.forClass(ToolExecutionResult.class);
        verify(logs).recordFailed(eq(100L), failure.capture());
        assertThat(failure.getValue().errorCode()).isEqualTo("TASK_BOUNDARY_ABORTED");
        verify(logs, never()).recordSuccess(any(), any());
    }

    @Test
    void shouldObserveCancellationWhileTheHandlerIsBlocked() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        CountDownLatch interrupted = new CountDownLatch(1);
        IllegalStateException cancellation = new IllegalStateException("cancelled during call");
        when(executor.execute(any(), any())).thenAnswer(invocation -> {
            cancelled.set(true);
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException ex) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return new BuiltinToolHandler.HandlerResult("late", mapper.createObjectNode());
        });
        assertThatThrownBy(() -> runtime.execute(command(frozen(current.inputSchema(), "builtin-v1", 5_000),
                NOW.plusSeconds(5), () -> {
                    if (cancelled.get()) {
                        throw cancellation;
                    }
                }))).isSameAs(cancellation);
        assertThat(interrupted.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        verify(logs).recordFailed(eq(100L), any());
        verify(logs, never()).recordSuccess(any(), any());
    }

    @Test
    void shouldBoundBlockedHandlerByCurrentEmergencyTimeoutCapAndInterruptIt() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        when(definitions.findActiveById(11L)).thenReturn(Optional.of(
                definition(current.inputSchema(), "builtin-v1", 150)));
        when(executor.execute(any(), any())).thenAnswer(invocation -> {
            entered.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException ex) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return new BuiltinToolHandler.HandlerResult("late", mapper.createObjectNode());
        });
        long started = System.nanoTime();
        assertThatThrownBy(() -> runtime.execute(command(frozen(current.inputSchema(), "builtin-v1", 5_000),
                NOW.plusSeconds(5), () -> { })))
                .isInstanceOfSatisfying(ToolTaskExecutionException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo("TOOL_TIMEOUT"));
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - started)).isLessThan(java.time.Duration.ofSeconds(2));
        assertThat(entered.getCount()).isZero();
        assertThat(interrupted.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        verify(logs).recordFailed(eq(100L), any());
        verify(logs, never()).recordSuccess(any(), any());
    }

    private void assertSnapshotRejected(ToolSnapshot frozen) {
        assertThatThrownBy(() -> runtime.execute(command(frozen, NOW.plusSeconds(5), () -> { })))
                .isInstanceOfSatisfying(ToolTaskExecutionException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo("TOOL_SNAPSHOT_MISMATCH"));
    }

    private ToolExecutionCommand command(ToolSnapshot frozen, Instant deadlineAt, Runnable boundary) {
        return ToolExecutionCommand.taskScoped(11L, 21L, 31L, 1L, 2L,
                snapshot(List.of(frozen)), arguments, deadlineAt, boundary);
    }

    private ToolSnapshot frozen(JsonNode schema, String implementation, int timeoutMs) {
        return new ToolSnapshot("11", "order_query", "Frozen tool name", "Frozen description", schema,
                ToolSchemaFingerprint.sha256(schema), implementation, timeoutMs);
    }

    private AgentTaskExecutionSnapshot snapshot(List<ToolSnapshot> tools) {
        return new AgentTaskExecutionSnapshot("agent-task-snapshot-v1",
                new AgentSnapshot("2", "frozen prompt", "ACTIVE", 4, 2, 4096, 60),
                new RuntimeSnapshot("agent-decision-json-v2", "agent-runtime-rules-v2", "test"),
                new ChatModelSnapshot("test", "openai-compatible", "frozen-model", BigDecimal.ZERO,
                        BigDecimal.ONE, 32768, true),
                new RetrievalSnapshot(List.of(), 5, BigDecimal.ZERO, false), tools);
    }

    private ToolDefinition definition(JsonNode schema, String implementation, int timeoutMs) {
        return new ToolDefinition(11L, "order_query", "Current name", "Current description", "BUILTIN", schema,
                mapper.createObjectNode(), mapper.createObjectNode().put("handler", "orderQueryTool")
                        .put("readonly", true).put("implementationVersion", implementation),
                timeoutMs, 0, false, "MEDIUM", "ACTIVE");
    }
}
