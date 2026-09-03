package com.agentflow.agent.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentflow.agent.snapshot.AgentTaskExecutionSnapshot;
import com.agentflow.agent.snapshot.AgentTaskSnapshotResolver;
import com.agentflow.agent.task.dispatch.AfterCommitTaskDispatchCoordinator;
import com.agentflow.agent.task.model.AgentTask;
import com.agentflow.agent.task.model.TaskEventType;
import com.agentflow.agent.task.repository.AgentTaskMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentTaskCreationTransactionServiceTest {
    @Mock
    private AgentTaskSnapshotResolver snapshotResolver;
    @Mock
    private AgentTaskMapper taskMapper;
    @Mock
    private TaskEventAppender eventAppender;
    @Mock
    private AfterCommitTaskDispatchCoordinator dispatchCoordinator;

    private AgentTaskCreationTransactionService service;

    @BeforeEach
    void setUp() {
        service = new AgentTaskCreationTransactionService(
                snapshotResolver,
                taskMapper,
                eventAppender,
                dispatchCoordinator,
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-09-02T01:02:03Z"), ZoneOffset.UTC)
        );
    }

    @Test
    void shouldFreezeSnapshotBudgetsAndAppendCreatedBeforeRegisteringDispatch() {
        CreateAgentTaskCommand command = new CreateAgentTaskCommand(
                11L,
                21L,
                "key-1",
                "  keep original input  "
        );
        when(snapshotResolver.resolve(11L, 21L)).thenReturn(snapshot());
        when(taskMapper.insertTask(any())).thenReturn(1);
        when(eventAppender.append(any(Long.class), eq(TaskEventType.TASK_CREATED), any()))
                .thenReturn(1L);

        AgentTask created = service.createNew(command, "a".repeat(64));

        ArgumentCaptor<AgentTask> persisted = ArgumentCaptor.forClass(AgentTask.class);
        verify(taskMapper).insertTask(persisted.capture());
        assertThat(persisted.getValue().getUserInput()).isEqualTo("  keep original input  ");
        assertThat(persisted.getValue().getExecutionSnapshot()).contains(
                "\"snapshotVersion\":\"agent-task-snapshot-v1\"",
                "\"maxTotalTokens\":8000"
        );
        assertThat(persisted.getValue().getReservedFinalTokens()).isEqualTo(2000);
        assertThat(created.getLastEventSequence()).isEqualTo(1L);
        verify(dispatchCoordinator).dispatchAfterCommit(created.getId());
    }

    private static AgentTaskExecutionSnapshot snapshot() {
        return new AgentTaskExecutionSnapshot(
                "agent-task-snapshot-v1",
                new AgentTaskExecutionSnapshot.AgentSnapshot(
                        "21", "system", "ACTIVE", 6, 4, 8000, 120
                ),
                new AgentTaskExecutionSnapshot.RuntimeSnapshot("decision-v1", "rules-v1", "a925d6b"),
                new AgentTaskExecutionSnapshot.ChatModelSnapshot(
                        "chat", "openai-compatible", "model", new BigDecimal("0.2"),
                        new BigDecimal("0.8"), 32768, true
                ),
                new AgentTaskExecutionSnapshot.RetrievalSnapshot(
                        List.of(), 5, new BigDecimal("0.2"), false
                ),
                List.of()
        );
    }
}
