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

        assertThat(service.createTask(command)).isSameAs(existing);
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

        assertThat(service.createTask(command)).isSameAs(winner);
        verify(queryService, org.mockito.Mockito.times(2))
                .findByUserAndClientRequestId(11L, "key-1");
    }

    private String fingerprint(CreateAgentTaskCommand command) {
        return new TaskRequestFingerprint(new ObjectMapper())
                .calculate(command.agentId(), command.userInput())
                .sha256();
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
