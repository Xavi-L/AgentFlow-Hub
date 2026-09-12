package com.agentflow.agent.task.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentflow.agent.snapshot.AgentTaskExecutionSnapshot;
import com.agentflow.agent.task.model.AgentTask;
import com.agentflow.agent.task.model.TaskStatus;
import com.agentflow.agent.task.model.TaskTerminationReason;
import com.agentflow.agent.task.model.TokenUsageQuality;
import com.agentflow.agent.task.service.AgentTaskLifecycleTransactionService;
import com.agentflow.agent.task.service.AgentTaskQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class TaskRunnerTest {
    @Test
    void shouldFreezeDeadlineArbitrationAndUsageBeforePersistence() throws Exception {
        var lifecycle = Mockito.mock(AgentTaskLifecycleTransactionService.class);
        var query = Mockito.mock(AgentTaskQueryService.class);
        var delegate = Mockito.mock(TaskExecutionDelegate.class);
        Clock clock = Mockito.mock(Clock.class);
        ObjectMapper mapper = new ObjectMapper();
        Instant started = Instant.parse("2026-09-05T01:00:00Z");
        when(clock.instant()).thenReturn(started);
        AgentTaskExecutionSnapshot snapshot = new AgentTaskExecutionSnapshot(
                "agent-task-snapshot-v1",
                new AgentTaskExecutionSnapshot.AgentSnapshot("21", "Frozen prompt", "ACTIVE", 6, 4, 8000, 120),
                new AgentTaskExecutionSnapshot.RuntimeSnapshot("agent-decision-json-v1", "agent-runtime-rules-v1", "test"),
                new AgentTaskExecutionSnapshot.ChatModelSnapshot("openai-compatible-default", "openai-compatible",
                        "frozen-model", BigDecimal.ZERO, BigDecimal.ONE, 32768, true),
                new AgentTaskExecutionSnapshot.RetrievalSnapshot(List.of(), 5, BigDecimal.ZERO, false), List.of());
        AgentTask task = new AgentTask();
        task.setId(1L);
        task.setUserId(11L);
        task.setAgentId(21L);
        task.setStatus(TaskStatus.RUNNING.name());
        task.setStartedAt(started.atOffset(ZoneOffset.UTC));
        task.setUserInput("Question");
        task.setExecutionSnapshot(mapper.writeValueAsString(snapshot));
        task.setMaxDecisionTurns(6);
        task.setMaxToolCalls(4);
        task.setMaxTotalTokens(8000);
        task.setReservedFinalTokens(2000);
        when(lifecycle.claim(1L)).thenReturn(task);
        AtomicBoolean cancellationRequested = new AtomicBoolean();
        when(query.hasCancellationRequest(1L)).thenAnswer(ignored -> cancellationRequested.get());
        TaskTokenUsage usage = new TaskTokenUsage(321, 123, TokenUsageQuality.EXACT);
        TaskExecutionOutcome completed = TaskExecutionOutcome.completed("Answer", TaskTerminationReason.ANSWERED,
                3, 2, usage, mapper.createArrayNode());
        when(delegate.execute(any())).thenAnswer(ignored -> {
            when(clock.instant()).thenReturn(started.plusSeconds(121));
            return completed;
        });
        var settlement = Mockito.mock(TaskSettlementService.class);

        var admission = new com.agentflow.agent.task.recovery.TaskExecutionAdmission();
        admission.open();
        new TaskRunner(lifecycle, query, delegate, mapper, clock, admission, settlement).run(1L);

        ArgumentCaptor<TaskExecutionOutcome> observed = ArgumentCaptor.forClass(TaskExecutionOutcome.class);
        verify(settlement).settle(eq(1L), observed.capture(), eq(started.plusSeconds(121)));
        assertThat(observed.getValue().resultType()).isEqualTo(TaskExecutionResultType.TIMED_OUT);
        assertThat(observed.getValue().tokenUsage()).isEqualTo(usage);
        assertThat(observed.getValue().decisionTurnsUsed()).isEqualTo(3);
        assertThat(observed.getValue().toolCallsUsed()).isEqualTo(2);
        verify(delegate).execute(any());
        verify(lifecycle, never()).complete(eq(1L), any());
        verify(lifecycle, never()).fail(eq(1L), any());
    }

    @Test
    void queuedExecutorWorkIsPersistentlyRejectedWhenAdmissionClosesBeforeClaim() {
        var lifecycle = Mockito.mock(AgentTaskLifecycleTransactionService.class);
        var query = Mockito.mock(AgentTaskQueryService.class);
        var delegate = Mockito.mock(TaskExecutionDelegate.class);
        var settlement = Mockito.mock(TaskSettlementService.class);
        var gate = new com.agentflow.agent.task.recovery.TaskExecutionAdmission();
        new TaskRunner(lifecycle, query, delegate, new ObjectMapper(), Clock.systemUTC(), gate, settlement)
                .runDispatched(1L);
        verify(settlement).rejectDispatch(1L);
        Mockito.verifyNoInteractions(lifecycle, query, delegate);
    }
}
