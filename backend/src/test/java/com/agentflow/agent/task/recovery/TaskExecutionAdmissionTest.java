package com.agentflow.agent.task.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.agentflow.agent.task.dispatch.BoundedTaskDispatcher;
import com.agentflow.agent.task.execution.TaskRunner;
import com.agentflow.agent.task.service.*;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.nio.file.Path;

class TaskExecutionAdmissionTest {
    @TempDir Path directory;

    @Test
    void rejectsEveryWriteEntryBeforeItsDependenciesAreCalled() {
        var gate = new TaskExecutionAdmission();
        var query = mock(AgentTaskQueryService.class);
        var creation = mock(AgentTaskCreationTransactionService.class);
        var fingerprints = mock(TaskRequestFingerprint.class);
        var app = new AgentTaskApplicationService(fingerprints, query, creation, gate);
        var tasks = mock(com.agentflow.agent.task.repository.AgentTaskMapper.class);
        var events = mock(TaskEventAppender.class);
        var resolver = mock(com.agentflow.agent.snapshot.AgentTaskSnapshotResolver.class);
        var afterCommit = mock(com.agentflow.agent.task.dispatch.AfterCommitTaskDispatchCoordinator.class);
        var lifecycle = new AgentTaskLifecycleTransactionService(tasks, events, new ObjectMapper(), Clock.systemUTC(), gate);
        var directCreation = new AgentTaskCreationTransactionService(resolver, tasks, events, afterCommit,
                new ObjectMapper(), Clock.systemUTC(), gate);
        var delegate = mock(com.agentflow.agent.task.execution.TaskExecutionDelegate.class);
        var runner = new TaskRunner(lifecycle, query, delegate, new ObjectMapper(), Clock.systemUTC(), gate);
        var executor = mock(ThreadPoolTaskExecutor.class);
        var dispatcher = new BoundedTaskDispatcher(executor, runner, gate);
        for (Runnable entry : List.<Runnable>of(
                () -> app.createTaskWithResult(null), () -> directCreation.createNew(null, "unused"),
                () -> lifecycle.requestCancellation(1, 2), () -> lifecycle.claim(2),
                () -> runner.run(2), () -> dispatcher.dispatch(2))) {
            assertThatThrownBy(entry::run).isInstanceOfSatisfying(BusinessException.class,
                    failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.TASK_EXECUTION_NOT_READY));
        }
        verifyNoInteractions(query, creation, fingerprints, tasks, events, resolver, afterCommit, delegate, executor);
    }

    @Test
    void publicCancellationRejectsBeforeTheTransactionCanAcquireAnUnavailableConnection() {
        var gate = new TaskExecutionAdmission();
        var lifecycle = mock(AgentTaskLifecycleTransactionService.class);
        var responses = mock(com.agentflow.agent.task.dto.AgentTaskResponseMapper.class);
        var user = mock(com.agentflow.user.security.AuthenticatedUser.class);
        when(user.id()).thenReturn(1L);
        var rest = new AgentTaskRestService(mock(AgentTaskApplicationService.class), lifecycle,
                mock(com.agentflow.agent.task.repository.AgentTaskMapper.class), responses,
                mock(com.agentflow.agent.trace.TaskTraceQueryService.class), gate);
        assertThatThrownBy(() -> rest.cancel(user, 2L)).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.TASK_EXECUTION_NOT_READY));
        verifyNoInteractions(lifecycle, responses);
    }

    @Test
    void shutdownCannotBeOvertakenByAStartupCompletion() {
        var gate = new TaskExecutionAdmission();
        gate.onShutdown();
        assertThatThrownBy(gate::open).isInstanceOf(IllegalStateException.class);
        gate.close("LATE_STARTUP_ERROR");
        assertThat(gate.diagnostic()).isEqualTo("SHUTTING_DOWN");
        assertThatThrownBy(gate::requireReady).isInstanceOf(BusinessException.class);
    }

    @Test
    void disabledModeRefusesLegacyTasksWithoutRecoveryWrites() {
        var gate = new TaskExecutionAdmission();
        var properties = new TaskRecoveryProperties();
        var mapper = mock(TaskRecoveryMapper.class);
        var tx = mock(TaskRecoveryTransactionService.class);
        when(mapper.hasCandidates()).thenReturn(true);
        var coordinator = new TaskRecoveryStartupCoordinator(properties, mock(TaskExecutionProcessLock.class), gate, mapper, tx);
        coordinator.run(null);
        assertThat(gate.isReady()).isFalse();
        assertThat(gate.diagnostic()).isEqualTo("DISABLED_WITH_LEGACY_TASKS");
        verifyNoInteractions(tx);
    }

    @Test
    void aFailedTaskClosesAdmissionAndDoesNotSilentlyContinue() {
        var gate = new TaskExecutionAdmission();
        var properties = new TaskRecoveryProperties();
        properties.setMode(TaskRecoveryProperties.Mode.CONTROLLED_SINGLE_HOST);
        var mapper = mock(TaskRecoveryMapper.class);
        var tx = mock(TaskRecoveryTransactionService.class);
        when(mapper.selectCandidateIds(0, 100)).thenReturn(List.of(1L, 2L, 3L));
        when(tx.recover(eq(2L), anyString())).thenThrow(new IllegalStateException("invariant"));
        new TaskRecoveryStartupCoordinator(properties, mock(TaskExecutionProcessLock.class), gate, mapper, tx).run(null);
        assertThat(gate.diagnostic()).isEqualTo("TASK_SETTLEMENT:task=2");
        verify(tx).recover(eq(1L), anyString());
        verify(tx, never()).recover(eq(3L), anyString());
    }

    @Test
    void candidateReadFailureNeverOpensAdmission() {
        var gate = new TaskExecutionAdmission();
        var mapper = mock(TaskRecoveryMapper.class);
        when(mapper.hasCandidates()).thenThrow(new IllegalStateException("database unavailable"));
        new TaskRecoveryStartupCoordinator(new TaskRecoveryProperties(), mock(TaskExecutionProcessLock.class), gate,
                mapper, mock(TaskRecoveryTransactionService.class)).run(null);
        assertThat(gate.diagnostic()).isEqualTo("CANDIDATE_READ");
    }

    @Test
    void lockIsRequiredEvenWhenRecoveryDisabledAndCannotBeReacquiredDuringJvmLifetime() {
        var properties = new TaskRecoveryProperties();
        assertThatThrownBy(() -> TaskExecutionProcessLock.acquire(properties)).isInstanceOf(IllegalStateException.class);
        properties.setLockPath(directory.resolve("domain.lock").toString());
        var held = TaskExecutionProcessLock.acquire(properties);
        held.requireHeld();
        assertThatThrownBy(() -> TaskExecutionProcessLock.acquire(properties)).isInstanceOf(IllegalStateException.class);
        held.requireHeld();
    }
}
