package com.agentflow.agent.task.service;

/** A durable-log discontinuity; a transport must close without skipping the missing sequence. */
public class TaskEventGapException extends RuntimeException {
    private final long expectedSequence;
    private final Long actualSequence;
    private final long lastEventSequence;

    public TaskEventGapException(long expectedSequence, Long actualSequence, long lastEventSequence) {
        super("Task event sequence gap detected");
        this.expectedSequence = expectedSequence;
        this.actualSequence = actualSequence;
        this.lastEventSequence = lastEventSequence;
    }

    public long expectedSequence() {
        return expectedSequence;
    }

    public Long actualSequence() {
        return actualSequence;
    }

    public long lastEventSequence() {
        return lastEventSequence;
    }
}
