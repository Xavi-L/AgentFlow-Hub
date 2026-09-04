package com.agentflow.agent.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentflow.agent.task.model.TaskEventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PersistentExecutionRecorderTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldBindAllStepAndEventOperationsToTheFactoryTask() {
        ExecutionRecorderTransactionService transactions = Mockito.mock(
                ExecutionRecorderTransactionService.class
        );
        ExecutionRecorder recorder = new PersistentExecutionRecorderFactory(transactions).open(4101L);
        StepHandle handle = new StepHandle(4101L, 4201L, 0, StepType.LLM_DECISION);
        StepSummary summary = new StepSummary(objectMapper.createObjectNode().put("decision", "FINISH"));
        TaskEventRecord event = new TaskEventRecord(
                TaskEventType.DECISION_FINISHED,
                objectMapper.createObjectNode().put("stepIndex", 0)
        );
        when(transactions.startStep(4101L, StepType.LLM_DECISION, "Decision"))
                .thenReturn(handle);

        assertThat(recorder.startStep(StepType.LLM_DECISION, "Decision")).isSameAs(handle);
        recorder.completeStep(handle, summary);
        recorder.appendEvent(event);

        verify(transactions).startStep(4101L, StepType.LLM_DECISION, "Decision");
        verify(transactions).completeStep(handle, summary);
        verify(transactions).appendEvent(4101L, event);
    }

    @Test
    void shouldRejectForeignTaskHandlesBeforeDelegatingAWrite() {
        ExecutionRecorderTransactionService transactions = Mockito.mock(
                ExecutionRecorderTransactionService.class
        );
        ExecutionRecorder recorder = new PersistentExecutionRecorderFactory(transactions).open(4101L);
        StepHandle foreign = new StepHandle(4199L, 4299L, 0, StepType.TOOL_CALL);
        StepSummary summary = new StepSummary(objectMapper.createObjectNode());

        assertThatThrownBy(() -> recorder.completeStep(foreign, summary))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Step belongs to a different task-bound recorder");
        assertThatThrownBy(() -> recorder.failStep(foreign, "TOOL_FAILED", "Safe failure"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Step belongs to a different task-bound recorder");

        verify(transactions, never()).completeStep(foreign, summary);
        verify(transactions, never()).failStep(foreign, "TOOL_FAILED", "Safe failure");
    }
}
