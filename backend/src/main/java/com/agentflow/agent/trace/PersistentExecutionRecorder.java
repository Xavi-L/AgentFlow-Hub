package com.agentflow.agent.trace;

import java.util.Objects;

/** Lightweight task-bound facade; all transaction work is delegated to a Spring proxy. */
public final class PersistentExecutionRecorder implements ExecutionRecorder {
    private final long taskId;
    private final ExecutionRecorderTransactionService transactions;

    PersistentExecutionRecorder(long taskId, ExecutionRecorderTransactionService transactions) {
        if (taskId <= 0) {
            throw new IllegalArgumentException("taskId must be positive");
        }
        this.taskId = taskId;
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
    }

    @Override
    public StepHandle startStep(StepType type, String title) {
        return transactions.startStep(taskId, type, title);
    }

    @Override
    public void completeStep(StepHandle step, StepSummary summary) {
        requireBoundStep(step);
        transactions.completeStep(step, summary);
    }

    @Override
    public void failStep(StepHandle step, String errorCode, String safeMessage) {
        requireBoundStep(step);
        transactions.failStep(step, errorCode, safeMessage);
    }

    @Override
    public void recordLlmCall(LlmCallRecord record) {
        Objects.requireNonNull(record, "record must not be null");
        requireBoundStep(record.step());
        transactions.recordLlmCall(record);
    }

    @Override
    public void recordRagRetrieval(RagRetrievalRecord record) {
        Objects.requireNonNull(record, "record must not be null");
        requireBoundStep(record.step());
        transactions.recordRagRetrieval(record);
    }

    @Override
    public void appendEvent(TaskEventRecord event) {
        transactions.appendEvent(taskId, event);
    }

    private void requireBoundStep(StepHandle step) {
        Objects.requireNonNull(step, "step must not be null");
        if (step.taskId() != taskId) {
            throw new IllegalArgumentException("Step belongs to a different task-bound recorder");
        }
    }
}
