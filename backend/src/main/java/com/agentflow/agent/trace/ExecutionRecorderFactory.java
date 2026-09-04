package com.agentflow.agent.trace;

/** Opens a lightweight recorder whose public contract is permanently bound to one task ID. */
public interface ExecutionRecorderFactory {
    ExecutionRecorder open(long taskId);
}
