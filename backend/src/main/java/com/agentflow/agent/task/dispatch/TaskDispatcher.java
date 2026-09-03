package com.agentflow.agent.task.dispatch;

/** Accepts one committed task for single-instance execution or throws before acceptance. */
@FunctionalInterface
public interface TaskDispatcher {
    void dispatch(long taskId);
}
