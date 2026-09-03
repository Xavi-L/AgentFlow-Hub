package com.agentflow.agent.task.dispatch;

public class TaskDispatchRejectedException extends RuntimeException {
    public TaskDispatchRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
