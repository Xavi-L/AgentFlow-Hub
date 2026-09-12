package com.agentflow.agent.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TaskExternalCallDeadlineTest {
    private final Clock clock = Clock.systemUTC();

    @Test
    void defaultCapacityIsFourAndCancelledAdmissionDoesNotLaunchWork() {
        assertThat(new TaskExternalCallDeadline(new TaskExecutionProperties()).capacity()).isEqualTo(4);
        AtomicInteger launched = new AtomicInteger();
        var calls = new TaskExternalCallDeadline(1, work -> launched.incrementAndGet());
        assertThatThrownBy(() -> calls.call(() -> 7, later(), clock, () -> { throw cancelled(); }))
                .isInstanceOf(TaskExecutionAbort.class);
        assertThat(launched).hasValue(0);
        assertThat(calls.availablePermits()).isEqualTo(1);
    }

    @Test
    void cancelledWaiterDoesNotReleaseUncooperativeBodyAndWaitingAdmissionCanCancel() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch exited = new CountDownLatch(1);
        AtomicBoolean cancel = new AtomicBoolean();
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        var calls = new TaskExternalCallDeadline(1, work -> Thread.ofVirtual().start(() -> {
            work.run();
            exited.countDown();
        }));
        Thread first = Thread.ofVirtual().start(() -> {
            try {
                calls.call(() -> {
                    entered.countDown();
                    while (release.getCount() > 0) {
                        try { release.await(); } catch (InterruptedException ignored) { }
                    }
                    return "late response";
                }, later(), clock, () -> { if (cancel.get()) throw cancelled(); });
            } catch (Throwable failure) { firstFailure.set(failure); }
        });
        try {
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            cancel.set(true);
            first.join(3_000);
            assertThat(first.isAlive()).isFalse();
            assertThat(firstFailure.get()).isInstanceOf(TaskExecutionAbort.class);
            assertThat(calls.activeWorkCount()).isEqualTo(1);
            assertThat(calls.availablePermits()).isZero();

            AtomicBoolean cancelWaiting = new AtomicBoolean();
            AtomicInteger nextWork = new AtomicInteger();
            CountDownLatch admissionReached = new CountDownLatch(1);
            AtomicReference<Throwable> secondFailure = new AtomicReference<>();
            Thread second = Thread.ofVirtual().start(() -> {
                try {
                    calls.call(nextWork::incrementAndGet, later(), clock, () -> {
                        admissionReached.countDown();
                        if (cancelWaiting.get()) throw cancelled();
                    });
                } catch (Throwable failure) { secondFailure.set(failure); }
            });
            assertThat(admissionReached.await(3, TimeUnit.SECONDS)).isTrue();
            cancelWaiting.set(true);
            second.join(3_000);
            assertThat(second.isAlive()).isFalse();
            assertThat(secondFailure.get()).isInstanceOf(TaskExecutionAbort.class);
            assertThat(nextWork).hasValue(0);
            assertThat(calls.activeWorkCount()).isEqualTo(1);
            assertThat(calls.availablePermits()).isZero();
        } finally {
            release.countDown();
        }
        assertThat(exited.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(calls.activeWorkCount()).isZero();
        assertThat(calls.availablePermits()).isEqualTo(1);
    }

    @Test
    void cancellationAfterAcquireBeforeBodyEntryReturnsExactlyOnePermit() throws Exception {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        CountDownLatch launched = new CountDownLatch(1);
        AtomicBoolean cancel = new AtomicBoolean();
        AtomicInteger actualCalls = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var calls = new TaskExternalCallDeadline(1, work -> { queued.set(work); launched.countDown(); });
        Thread waiter = Thread.ofVirtual().start(() -> {
            try {
                calls.call(actualCalls::incrementAndGet, later(), clock,
                        () -> { if (cancel.get()) throw cancelled(); });
            } catch (Throwable ex) { failure.set(ex); }
        });
        assertThat(launched.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(calls.availablePermits()).isZero();
        assertThat(calls.activeWorkCount()).isZero();
        cancel.set(true);
        waiter.join(3_000);
        assertThat(waiter.isAlive()).isFalse();
        assertThat(failure.get()).isInstanceOf(TaskExecutionAbort.class);
        queued.get().run();
        queued.get().run();
        assertThat(actualCalls).hasValue(0);
        assertThat(calls.availablePermits()).isEqualTo(1);
        assertThat(calls.activeWorkCount()).isZero();
    }

    @Test
    void cancelAtPostAcquireBoundaryAndLaunchFailureCannotLeakPermits() {
        AtomicInteger checks = new AtomicInteger();
        AtomicInteger launched = new AtomicInteger();
        var calls = new TaskExternalCallDeadline(1, work -> launched.incrementAndGet());
        assertThatThrownBy(() -> calls.call(() -> 7, later(), clock, () -> {
            if (checks.incrementAndGet() == 2) throw cancelled();
        })).isInstanceOf(TaskExecutionAbort.class);
        assertThat(launched).hasValue(0);
        assertThat(calls.availablePermits()).isEqualTo(1);
        var rejectedLaunch = new TaskExternalCallDeadline(1, work -> { throw new IllegalStateException("rejected"); });
        assertThatThrownBy(() -> rejectedLaunch.call(() -> 7, later(), clock, () -> { }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(rejectedLaunch.availablePermits()).isEqualTo(1);
    }

    @Test
    void normalReturnIsObservedBeforeCancellationAndLateReturnIsNotObserved() {
        AtomicBoolean cancel = new AtomicBoolean();
        AtomicInteger observed = new AtomicInteger();
        var calls = new TaskExternalCallDeadline(new TaskExecutionProperties());
        assertThatThrownBy(() -> calls.call(() -> { cancel.set(true); return 23; }, later(), clock,
                () -> { if (cancel.get()) throw cancelled(); }, observed::set,
                () -> new TaskExecutionAbort("AGENT_LLM_TIMEOUT", "Model call exceeded its time limit"),
                TaskExternalCallDeadlineTest::cancelled)).isInstanceOf(TaskExecutionAbort.class);
        assertThat(observed).hasValue(23);
        assertThat(calls.availablePermits()).isEqualTo(4);
    }

    @Test
    void nestingUsesOnePermitAndGrandchildRetainsImmediateParentBoundary() {
        var calls = new TaskExternalCallDeadline(1, work -> Thread.ofVirtual().start(work));
        AtomicInteger invoked = new AtomicInteger();
        String value = calls.call(() -> calls.call(() -> {
            assertThat(calls.activeWorkCount()).isEqualTo(1);
            assertThat(calls.availablePermits()).isZero();
            invoked.incrementAndGet();
            return "nested";
        }, later(), clock, () -> { }), later(), clock, () -> { });
        assertThat(value).isEqualTo("nested");
        assertThat(invoked).hasValue(1);
        assertThat(calls.availablePermits()).isEqualTo(1);

        AtomicBoolean middleCancelled = new AtomicBoolean();
        assertThatThrownBy(() -> calls.call(() -> calls.call(() -> {
            middleCancelled.set(true);
            return calls.call(invoked::incrementAndGet, later(), clock, () -> { });
        }, later(), clock, () -> { if (middleCancelled.get()) throw cancelled(); }), later(), clock, () -> { }))
                .isInstanceOf(TaskExecutionAbort.class);
        assertThat(invoked).hasValue(1);
        assertThat(calls.availablePermits()).isEqualTo(1);
    }

    private Instant later() { return clock.instant().plusSeconds(10); }
    private static TaskExecutionAbort cancelled() { return new TaskExecutionAbort("TASK_CANCELLED", "Cancelled"); }
}
