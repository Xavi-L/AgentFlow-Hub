package com.agentflow.agent.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agentflow.agent.task.dto.SafeTaskEventProjector;
import com.agentflow.agent.task.dto.SafeTaskEventResponse;
import com.agentflow.agent.task.dto.SafeTaskPayloadProjector;
import com.agentflow.agent.task.model.AgentTask;
import com.agentflow.agent.task.model.AgentTaskEvent;
import com.agentflow.agent.task.model.TaskStatus;
import com.agentflow.agent.task.repository.AgentTaskEventMapper;
import com.agentflow.agent.task.repository.AgentTaskMapper;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskEventQueryServiceTest {
    private final AgentTaskMapper tasks = mock(AgentTaskMapper.class);
    private final AgentTaskEventMapper events = mock(AgentTaskEventMapper.class);
    private final ObjectMapper json = new ObjectMapper();
    private final TaskEventQueryService queries = new TaskEventQueryService(tasks, events,
            new SafeTaskEventProjector(new SafeTaskPayloadProjector(json), json));

    @Test
    void shouldReplayAcrossFullBatchBoundariesWithoutMistakingTheWatermarkForSentProgress() {
        ownedTask("COMPLETED", 5);
        when(events.selectBatchByTaskIdAfterSequence(91, 0, 5, 2)).thenReturn(List.of(event(1), event(2)));
        when(events.selectBatchByTaskIdAfterSequence(91, 2, 5, 2)).thenReturn(List.of(event(3), event(4)));
        when(events.selectBatchByTaskIdAfterSequence(91, 4, 5, 2)).thenReturn(List.of(event(5)));

        var first = queries.findOwnedBatch(7, 91, 0, 2);
        var second = queries.findOwnedBatch(7, 91, first.events().getLast().sequenceNo(), 2);
        var last = queries.findOwnedBatch(7, 91, second.events().getLast().sequenceNo(), 2);

        assertThat(first.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(first.lastEventSequence()).isEqualTo(5);
        assertThat(first.events()).extracting(SafeTaskEventResponse::sequenceNo).containsExactly(1L, 2L);
        assertThat(second.events()).extracting(SafeTaskEventResponse::sequenceNo).containsExactly(3L, 4L);
        assertThat(last.events()).extracting(SafeTaskEventResponse::sequenceNo).containsExactly(5L);
        assertThatThrownBy(() -> first.events().clear()).isInstanceOf(UnsupportedOperationException.class);
        verify(events).selectBatchByTaskIdAfterSequence(91, 0, 5, 2);
    }

    @Test
    void shouldKeepMissingAndCrossOwnerTasksIndistinguishableEvenWithAnAheadCursor() {
        for (long userId : List.of(7L, 8L)) {
            assertThatThrownBy(() -> queries.findOwnedBatch(userId, 91, Long.MAX_VALUE, 2))
                    .isInstanceOfSatisfying(BusinessException.class,
                            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.COMMON_NOT_FOUND));
        }
        verifyNoInteractions(events);
    }

    @Test
    void shouldRejectNegativeAndAheadCursorsWithoutReadingEvents() {
        ownedTask("RUNNING", 5);
        for (long cursor : List.of(-1L, 6L, Long.MAX_VALUE)) {
            assertThatThrownBy(() -> queries.findOwnedBatch(7, 91, cursor, 2))
                    .isInstanceOfSatisfying(BusinessException.class,
                            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.COMMON_PARAM_INVALID));
        }
        verifyNoInteractions(events);
    }

    @Test
    void shouldRejectUnboundedAndEmptyPageRequests() {
        ownedTask("RUNNING", 5);
        for (int limit : List.of(0, -1, TaskEventQueryService.MAX_BATCH_SIZE + 1)) {
            assertThatThrownBy(() -> queries.findOwnedBatch(7, 91, 0, limit))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(events);
    }

    @Test
    void shouldRejectAMissingFirstSequenceBeforeExposingAnyPage() {
        ownedTask("RUNNING", 5);
        when(events.selectBatchByTaskIdAfterSequence(91, 0, 5, 2)).thenReturn(List.of(event(2), event(3)));
        assertGap(0, 2, 1, 2L, 5);
    }

    @Test
    void shouldRejectAGapInsideAFullPageBeforeExposingItsPrefix() {
        ownedTask("RUNNING", 5);
        when(events.selectBatchByTaskIdAfterSequence(91, 0, 5, 3))
                .thenReturn(List.of(event(1), event(2), event(4)));
        assertGap(0, 3, 3, 4L, 5);
    }

    @Test
    void shouldRejectMissingTailAndEmptyPageBelowTheWatermark() {
        ownedTask("COMPLETED", 5);
        when(events.selectBatchByTaskIdAfterSequence(91, 2, 5, 4)).thenReturn(List.of(event(3)));
        assertGap(2, 4, 4, null, 5);
        when(events.selectBatchByTaskIdAfterSequence(91, 4, 5, 2)).thenReturn(List.of());
        assertGap(4, 2, 5, null, 5);
    }

    @Test
    void shouldAcceptAnEmptyPageAtTheWatermarkWithoutOverflowingTheCursor() {
        ownedTask("COMPLETED", Long.MAX_VALUE);
        when(events.selectBatchByTaskIdAfterSequence(91, Long.MAX_VALUE, Long.MAX_VALUE, 2))
                .thenReturn(List.of());
        var batch = queries.findOwnedBatch(7, 91, Long.MAX_VALUE, 2);
        assertThat(batch.status().isTerminal()).isTrue();
        assertThat(batch.events()).isEmpty();
        assertThat(batch.lastEventSequence()).isEqualTo(Long.MAX_VALUE);
    }

    private void ownedTask(String status, long watermark) {
        AgentTask task = new AgentTask();
        task.setId(91L);
        task.setUserId(7L);
        task.setStatus(status);
        task.setLastEventSequence(watermark);
        when(tasks.selectOwnedById(91, 7)).thenReturn(task);
    }

    private AgentTaskEvent event(long sequence) {
        AgentTaskEvent event = new AgentTaskEvent();
        event.setId(100L + sequence);
        event.setTaskId(91L);
        event.setSequenceNo(sequence);
        event.setEventType("PHASE_CHANGED");
        event.setPayload("{\"phase\":\"DECIDING\"}");
        return event;
    }

    private void assertGap(long cursor, int limit, long expected, Long actual, long watermark) {
        assertThatThrownBy(() -> queries.findOwnedBatch(7, 91, cursor, limit))
                .isInstanceOfSatisfying(TaskEventGapException.class, ex -> {
                    assertThat(ex.expectedSequence()).isEqualTo(expected);
                    assertThat(ex.actualSequence()).isEqualTo(actual);
                    assertThat(ex.lastEventSequence()).isEqualTo(watermark);
                });
    }
}
