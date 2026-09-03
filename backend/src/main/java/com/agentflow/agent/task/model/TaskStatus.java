package com.agentflow.agent.task.model;

public enum TaskStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    TIMED_OUT;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED || this == TIMED_OUT;
    }
}
