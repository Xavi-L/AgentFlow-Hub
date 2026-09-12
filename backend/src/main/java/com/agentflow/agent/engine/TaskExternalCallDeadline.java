package com.agentflow.agent.engine;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * One process-wide bound on task external work, including work that ignores interruption.
 * LLM, the synchronous RAG pass and the tool handler each have exactly one owner.
 * A cancelled Future is not evidence that its underlying work has exited.
 */
@Component
public final class TaskExternalCallDeadline {
    private static final Logger log = LoggerFactory.getLogger(TaskExternalCallDeadline.class);
    private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
    private final Semaphore permits;
    private final int capacity;
    private final AtomicInteger active = new AtomicInteger();
    private final ThreadLocal<Boundary> ownedBoundary = new ThreadLocal<>();
    private final Consumer<Runnable> launch;

    @Autowired
    public TaskExternalCallDeadline(TaskExecutionProperties properties) {
        this(properties.getMaxConcurrentExternalCalls(),
                work -> Thread.ofVirtual().name("agent-task-external-call").start(work));
    }

    /** Controlled launch seam verifies cancellation after admission but before body entry. */
    TaskExternalCallDeadline(int capacity, Consumer<Runnable> launch) {
        if (capacity < 1 || capacity > 64) throw new IllegalArgumentException("External work capacity must be 1..64");
        this.capacity = capacity;
        this.permits = new Semaphore(capacity, true);
        this.launch = Objects.requireNonNull(launch);
    }

    public int capacity() { return capacity; }
    public int activeWorkCount() { return active.get(); }
    public int availablePermits() { return permits.availablePermits(); }

    /** Guards sequential I/O inside a body whose waiter may already have stopped waiting. */
    public void checkCurrentWorkBoundary() {
        Boundary current = ownedBoundary.get();
        if (current != null) current.check();
    }

    public <T> T call(Callable<T> action, Instant deadline, Clock clock, Runnable boundary) {
        return call(action, deadline, clock, boundary, ignored -> { },
                () -> new TaskExecutionAbort("TASK_TIMED_OUT", "Task deadline was exceeded"),
                () -> new TaskExecutionAbort("AGENT_EXECUTION_INTERRUPTED", "Task execution was interrupted"));
    }

    public <T> T call(Callable<T> action, Instant deadline, Clock clock, Runnable boundary,
            Consumer<T> observe, Supplier<? extends RuntimeException> timeout,
            Supplier<? extends RuntimeException> interrupted) {
        Boundary limit = new Boundary(deadline, clock, boundary, timeout, ownedBoundary.get());
        limit.check();
        // Nested synchronous work belongs to this same actual body. Keep its parent's
        // cancellation/deadline and never wait for a second permit (capacity may be one).
        if (ownedBoundary.get() != null) {
            Boundary previous = ownedBoundary.get();
            ownedBoundary.set(limit);
            try {
                T value = action.call();
                observe.accept(value);
                limit.check();
                return value;
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new TaskExecutionAbort("AGENT_EXTERNAL_CALL_FAILED", "External call failed");
            } finally {
                ownedBoundary.set(previous);
            }
        }

        Lease lease = null;
        FutureTask<T> pending = null;
        try {
            while (!permits.tryAcquire(Math.min(POLL_NANOS, limit.remaining()), TimeUnit.NANOSECONDS)) {
                limit.check();
            }
            lease = new Lease();
            limit.check();
            Lease owned = lease;
            pending = new FutureTask<>(() -> {
                if (!owned.enter()) throw interrupted.get();
                ownedBoundary.set(limit);
                try {
                    limit.check();
                    return action.call();
                } finally {
                    ownedBoundary.remove();
                    owned.exit();
                }
            });
            launch.accept(pending);
            while (true) {
                try {
                    T value = pending.get(Math.max(0, Math.min(POLL_NANOS, limit.monotonicRemaining())),
                            TimeUnit.NANOSECONDS);
                    // Observation belongs to the waiter. Preserve available usage before
                    // cancellation/time arbitration, without publishing any late response.
                    observe.accept(value);
                    limit.check();
                    return value;
                } catch (TimeoutException waiting) {
                    limit.check();
                }
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw interrupted.get();
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new TaskExecutionAbort("AGENT_EXTERNAL_CALL_FAILED", "External call failed");
        } finally {
            limit.abandoned.set(true);
            if (pending != null) pending.cancel(true);
            // Only a body that never entered may release here. An entered body releases
            // from its own finally, even when Future.cancel already reports completion.
            if (lease != null) lease.cancelBeforeEntry();
            if (lease != null && lease.state.get() == 1) {
                log.warn("TASK_EXTERNAL_WORK_STILL_RUNNING active={} capacity={}", active.get(), capacity);
            }
        }
    }

    private final class Lease {
        // 0 = admitted but not entered; 1 = entered; 2 = released.
        private final AtomicInteger state = new AtomicInteger();
        boolean enter() {
            if (!state.compareAndSet(0, 1)) return false;
            active.incrementAndGet();
            return true;
        }
        void exit() {
            if (state.compareAndSet(1, 2)) {
                active.decrementAndGet();
                permits.release();
            }
        }
        void cancelBeforeEntry() {
            if (state.compareAndSet(0, 2)) permits.release();
        }
    }

    private static final class Boundary {
        private final Instant deadline;
        private final Clock clock;
        private final Runnable boundary;
        private final Supplier<? extends RuntimeException> timeout;
        private final Boundary parent;
        private final AtomicBoolean abandoned = new AtomicBoolean();
        private final long allowance;
        private final long started = System.nanoTime();

        Boundary(Instant deadline, Clock clock, Runnable boundary,
                Supplier<? extends RuntimeException> timeout, Boundary parent) {
            this.deadline = deadline;
            this.clock = clock;
            this.boundary = boundary;
            this.timeout = timeout;
            this.parent = parent;
            this.allowance = Duration.between(clock.instant(), deadline).toNanos();
        }
        void check() {
            if (parent != null) parent.check();
            if (abandoned.get()) {
                throw new TaskExecutionAbort("AGENT_EXECUTION_INTERRUPTED", "External call observation has ended");
            }
            boundary.run();
            remaining();
        }
        long remaining() {
            long left = monotonicRemaining();
            if (left <= 0 || !clock.instant().isBefore(deadline)) throw timeout.get();
            return left;
        }
        long monotonicRemaining() { return allowance - Math.max(0, System.nanoTime() - started); }
    }
}
