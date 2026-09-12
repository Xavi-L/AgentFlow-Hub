package com.agentflow.agent.task.execution;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.agentflow.agent.task.model.AgentTask;
import com.agentflow.agent.task.model.TokenUsageQuality;
import com.agentflow.agent.task.recovery.TaskExecutionAdmission;
import com.agentflow.agent.task.service.AgentTaskLifecycleTransactionService;
import com.agentflow.agent.task.service.AgentTaskQueryService;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessResourceException;

class TaskSettlementServiceTest {
    final AgentTaskLifecycleTransactionService lifecycle = mock(AgentTaskLifecycleTransactionService.class);
    final AgentTaskQueryService query = mock(AgentTaskQueryService.class);
    final TaskExecutionAdmission admission = new TaskExecutionAdmission();
    final List<Long> waits = new ArrayList<>();
    final TaskSettlementService service = new TaskSettlementService(lifecycle, query, admission, waits::add);
    final Instant observedAt = Instant.parse("2026-09-12T01:00:00Z");
    final TaskExecutionOutcome outcome = TaskExecutionOutcome.completed("Original answer",
            com.agentflow.agent.task.model.TaskTerminationReason.ANSWERED, 2, 1,
            new TaskTokenUsage(31, 19, TokenUsageQuality.EXACT), null);

    @BeforeEach void setup() {
        admission.open();
        when(query.findById(1)).thenReturn(task("RUNNING"));
    }

    @Test void retriesOnlyTheSameObservedOutcomeWithFrozenTimeAndUsage() {
        when(lifecycle.settleObserved(1, outcome, observedAt))
                .thenThrow(transientFailure("40001")).thenThrow(transientFailure("55P03")).thenReturn(true);
        assertThat(service.settle(1, outcome, observedAt)).isTrue();
        verify(lifecycle, times(3)).settleObserved(1, outcome, observedAt);
        verify(query, times(5)).findById(1); // initial + immediate failure readback + each retry read
        assertThat(waits).containsExactly(100L, 500L);
        assertThat(admission.isReady()).isTrue();
        verifyNoMoreInteractions(lifecycle);
    }

    @Test void acceptsCommitWhoseAcknowledgementWasLostWithoutAnotherWrite() {
        when(lifecycle.settleObserved(1, outcome, observedAt)).thenThrow(transientFailure("08006"));
        when(query.findById(1)).thenReturn(task("RUNNING"), task("COMPLETED"));
        assertThat(service.settle(1, outcome, observedAt)).isTrue();
        verify(lifecycle).settleObserved(1, outcome, observedAt);
        assertThat(waits).isEmpty();
        assertThat(admission.isReady()).isTrue();
    }

    @Test void acceptsAnotherCommittedTerminalBeforeRetry() {
        when(lifecycle.settleObserved(1, outcome, observedAt)).thenThrow(transientFailure("40001"));
        when(query.findById(1)).thenReturn(task("RUNNING"), task("RUNNING"), task("CANCELLED"));
        assertThat(service.settle(1, outcome, observedAt)).isTrue();
        verify(lifecycle).settleObserved(1, outcome, observedAt);
        assertThat(waits).containsExactly(100L);
    }

    @Test void permanentDataErrorDoesNotRetryOrInventAnotherOutcome() {
        when(lifecycle.settleObserved(1, outcome, observedAt))
                .thenThrow(new DataIntegrityViolationException("private SQL value", new SQLException("secret", "23514")));
        assertThat(service.settle(1, outcome, observedAt)).isFalse();
        verify(lifecycle).settleObserved(1, outcome, observedAt);
        assertThat(waits).isEmpty();
        assertThat(admission.diagnostic()).isEqualTo("TASK_SETTLEMENT_PERSIST_FAILED:task=1:attempts=1:stage=TERMINAL_TRANSACTION");
        assertThatThrownBy(admission::open).isInstanceOf(IllegalStateException.class);
    }

    @Test void repeatedUnavailableDatabaseHasThreeBoundedAttemptsAndClosesAdmission() {
        when(query.findById(1)).thenThrow(transientFailure("08006"));
        assertThat(service.settle(1, outcome, observedAt)).isFalse();
        verify(query, times(3)).findById(1);
        verifyNoInteractions(lifecycle);
        assertThat(waits).containsExactly(100L, 500L);
        assertThat(admission.diagnostic()).contains("attempts=3:stage=READ_BACK");
    }

    @Test void finalWriteFailureStillReadsBackUnknownCommit() {
        when(lifecycle.settleObserved(1, outcome, observedAt)).thenThrow(transientFailure("08006"));
        when(query.findById(1)).thenReturn(task("RUNNING"), task("RUNNING"), task("RUNNING"),
                task("RUNNING"), task("RUNNING"), task("COMPLETED"));
        assertThat(service.settle(1, outcome, observedAt)).isTrue();
        verify(lifecycle, times(3)).settleObserved(1, outcome, observedAt);
        assertThat(admission.isReady()).isTrue();
    }

    @Test void persistenceCanRunAfterBusinessWaitWasInterruptedAndRestoresTheFlag() {
        when(lifecycle.settleObserved(1, outcome, observedAt)).thenReturn(true);
        Thread.currentThread().interrupt();
        try {
            assertThat(service.settle(1, outcome, observedAt)).isTrue();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    @Test void interruptedBackoffExitsWithoutAnUnboundedRetry() {
        when(lifecycle.settleObserved(1, outcome, observedAt)).thenThrow(transientFailure("40001"));
        var stopped = new TaskSettlementService(lifecycle, query, admission, millis -> { throw new InterruptedException(); });
        try {
            assertThat(stopped.settle(1, outcome, observedAt)).isFalse();
            assertThat(admission.diagnostic()).contains("attempts=1:stage=BACKOFF_INTERRUPTED");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    @Test void committedQueuedTaskUsesBoundedPersistentDispatchRejectionWhenAdmissionCloses() {
        admission.close("runtime failure");
        when(query.findById(1)).thenReturn(task("QUEUED"));
        when(lifecycle.markDispatchRejected(1)).thenThrow(transientFailure("40001")).thenReturn(true);
        assertThat(service.rejectDispatch(1)).isTrue();
        verify(lifecycle, times(2)).markDispatchRejected(1);
        verify(lifecycle, never()).settleObserved(anyLong(), any(), any());
        assertThat(admission.isReady()).isFalse();
    }

    @Test void failedDispatchCompensationEmitsTheSameDegradationDiagnostic() {
        when(query.findById(1)).thenReturn(task("QUEUED"));
        when(lifecycle.markDispatchRejected(1)).thenThrow(transientFailure("08006"));
        assertThat(service.rejectDispatch(1)).isFalse();
        verify(lifecycle, times(3)).markDispatchRejected(1);
        assertThat(admission.diagnostic()).contains("attempts=3:stage=DISPATCH_REJECTION");
    }

    @Test void classifierRequiresRecognizableDatabaseFailureAndRejectsPermanentSqlState() {
        assertThat(TaskSettlementService.transientDatabaseFailure(new IllegalStateException("not database"))).isFalse();
        assertThat(TaskSettlementService.transientDatabaseFailure(new DataAccessResourceFailureException("lost response"))).isTrue();
        assertThat(TaskSettlementService.transientDatabaseFailure(new TransientDataAccessResourceException("wrapper",
                new SQLException("constraint", "23514")))).isFalse();
        assertThat(TaskSettlementService.transientDatabaseFailure(transientFailure("40P01"))).isTrue();
        assertThat(TaskSettlementService.transientDatabaseFailure(transientFailure("42601"))).isFalse();
    }

    static AgentTask task(String status) { var task = new AgentTask(); task.setId(1L); task.setStatus(status); return task; }
    static DataAccessResourceFailureException transientFailure(String state) {
        return new DataAccessResourceFailureException("controlled database failure", new SQLException("controlled", state));
    }
}
