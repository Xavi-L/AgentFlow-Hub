package com.agentflow.agent.task.recovery;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Closed from construction; only successful startup settlement opens task writes. */
@Component
public class TaskExecutionAdmission {
    private boolean shuttingDown;
    private volatile String diagnostic = "STARTING";
    public boolean isReady() { return diagnostic == null; }
    public String diagnostic() { return diagnostic; }
    public void requireReady() {
        if (!isReady()) throw new BusinessException(ErrorCode.TASK_EXECUTION_NOT_READY);
    }
    public synchronized void open() {
        if (shuttingDown) throw new IllegalStateException("Task admission cannot reopen during JVM shutdown");
        diagnostic = null;
    }
    public synchronized void close(String reason) {
        diagnostic = shuttingDown ? "SHUTTING_DOWN" : reason == null ? "NOT_READY" : reason;
    }
    @EventListener(ContextClosedEvent.class)
    public synchronized void onShutdown() {
        shuttingDown = true;
        diagnostic = "SHUTTING_DOWN";
    }
}
