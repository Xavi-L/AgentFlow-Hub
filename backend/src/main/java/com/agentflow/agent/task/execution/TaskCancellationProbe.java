package com.agentflow.agent.task.execution;

@FunctionalInterface
public interface TaskCancellationProbe {
    boolean isCancellationRequested();
}
