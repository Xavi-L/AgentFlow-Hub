package com.agentflow.agent.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentflow.agent.task.model.AgentTask;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
class AgentTaskApplicationServiceTest {
    @Mock
    private AgentTaskQueryService queryService;
    @Mock
    private AgentTaskCreationTransactionService creationTransactions;

    private AgentTaskApplicationService service;

    @BeforeEach
    void setUp() {
        service = new AgentTaskApplicationService(
                new TaskRequestFingerprint(new ObjectMapper()),
                queryService,
                creationTransactions
        );
    }

    @Test
    void shouldReturnSameTaskWithoutCreatingOrDispatchingAgain() {
        CreateAgentTaskCommand command = command("original");
        AgentTask existing = task(91L, fingerprint(command));
        when(queryService.findByUserAndClientRequestId(11L, "key-1")).thenReturn(existing);

        existing.setStatus("QUEUED");
        CreateAgentTaskResult result = service.createTaskWithResult(command);
        assertThat(result.task()).isSameAs(existing);
        assertThat(result.created()).isFalse();
        verify(creationTransactions, never()).createNew(command, existing.getRequestFingerprint());
    }

    @Test
    void shouldRejectReuseOfTheKeyForDifferentPayload() {
        CreateAgentTaskCommand command = command("new payload");
        when(queryService.findByUserAndClientRequestId(11L, "key-1"))
                .thenReturn(task(91L, fingerprint(command("old payload"))));

        assertThatThrownBy(() -> service.createTask(command))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.TASK_IDEMPOTENCY_CONFLICT));
        verify(creationTransactions, never()).createNew(command, fingerprint(command));
    }

    @Test
    void shouldRecoverTheConcurrentWinnerOnlyAfterTheFailedCreationReturns() {
        CreateAgentTaskCommand command = command("same payload");
        String fingerprint = fingerprint(command);
        AgentTask winner = task(92L, fingerprint);
        when(queryService.findByUserAndClientRequestId(11L, "key-1"))
                .thenReturn(null, winner);
        when(creationTransactions.createNew(command, fingerprint))
                .thenThrow(new DuplicateKeyException("concurrent unique key"));

        CreateAgentTaskResult result = service.createTaskWithResult(command);
        assertThat(result.task()).isSameAs(winner);
        assertThat(result.created()).isFalse();
        verify(queryService, org.mockito.Mockito.times(2))
                .findByUserAndClientRequestId(11L, "key-1");
    }

    @Test
    void shouldReportSuccessfulInsertAsCreatedIndependentOfTaskStatus() {
        CreateAgentTaskCommand command = command("new payload");
        AgentTask created = task(93L, fingerprint(command));
        created.setStatus("FAILED"); // A fast after-commit dispatch rejection cannot turn 201 into 200.
        when(creationTransactions.createNew(command, fingerprint(command))).thenReturn(created);

        CreateAgentTaskResult result = service.createTaskWithResult(command);
        assertThat(result.created()).isTrue();
        assertThat(result.task()).isSameAs(created);
    }

    @Test
    void shouldRejectDifferentConcurrentWinnerAndPropagateUnrelatedConstraintFailure() {
        CreateAgentTaskCommand command = command("same payload");
        var failure = new DuplicateKeyException("concurrent unique key");
        when(creationTransactions.createNew(command, fingerprint(command))).thenThrow(failure);
        when(queryService.findByUserAndClientRequestId(11L, "key-1"))
                .thenReturn(null, task(94L, fingerprint(command("other payload"))));
        assertThatThrownBy(() -> service.createTaskWithResult(command))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.TASK_IDEMPOTENCY_CONFLICT));

        when(queryService.findByUserAndClientRequestId(11L, "key-1")).thenReturn(null);
        assertThatThrownBy(() -> service.createTaskWithResult(command)).isSameAs(failure);
    }

    private String fingerprint(CreateAgentTaskCommand command) {
        return new TaskRequestFingerprint(new ObjectMapper())
                .calculate(command.agentId(), command.userInput())
                .sha256();
    }

    @Test
    void shouldRejectTextThatPostgresCannotPersistBeforeFingerprintOrDatabaseAccess() {
        assertThatThrownBy(() -> service.createTaskWithResult(command("input\0suffix")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.COMMON_PARAM_INVALID));
        org.mockito.Mockito.verifyNoInteractions(queryService, creationTransactions);
    }

    private static CreateAgentTaskCommand command(String input) {
        return new CreateAgentTaskCommand(11L, 21L, "key-1", input);
    }

    private static AgentTask task(long id, String fingerprint) {
        AgentTask task = new AgentTask();
        task.setId(id);
        task.setRequestFingerprint(fingerprint);
        return task;
    }
}
