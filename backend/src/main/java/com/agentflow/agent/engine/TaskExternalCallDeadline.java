package com.agentflow.agent.engine;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounds the caller's wait; interruption of underlying I/O remains cooperative. */
final class TaskExternalCallDeadline {
    private TaskExternalCallDeadline() { }

    static <T> T call(Callable<T> action, Instant deadline, Clock clock, Runnable boundary) {
        return call(action, deadline, clock, boundary, "TASK_TIMED_OUT", "Task deadline was exceeded");
    }

    static <T> T call(Callable<T> action, Instant deadline, Clock clock, Runnable boundary,
            String timeoutCode, String timeoutMessage) {
        long allowedNanos = Duration.between(clock.instant(), deadline).toNanos();
        if (allowedNanos <= 0) throw new TaskExecutionAbort(timeoutCode, timeoutMessage);
        long started = System.nanoTime();
        FutureTask<T> pending = new FutureTask<>(action);
        Thread.ofVirtual().name("agent-task-external-call").start(pending);
        try {
            while (true) {
                long remaining = allowedNanos - (System.nanoTime() - started);
                if (remaining <= 0) throw new TaskExecutionAbort(timeoutCode, timeoutMessage);
                try {
                    // On a normal return the caller must first account measured usage, then
                    // check cancellation. During a blocked call inspect its boundary promptly.
                    return pending.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)), TimeUnit.NANOSECONDS);
                } catch (TimeoutException waiting) {
                    boundary.run();
                }
            }
        } catch (InterruptedException ex) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new TaskExecutionAbort("AGENT_EXECUTION_INTERRUPTED", "Task execution was interrupted");
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new TaskExecutionAbort("AGENT_EXTERNAL_CALL_FAILED", "External call failed");
        } catch (RuntimeException ex) {
            pending.cancel(true);
            throw ex;
        }
    }

}
