package com.agentflow.agent.task.execution;

/** M4C seam for scripted lifecycle outcomes; M4E will provide the real Agent integration. */
@FunctionalInterface
public interface TaskExecutionDelegate {
    TaskExecutionOutcome execute(TaskExecutionRequest request) throws Exception;
}
