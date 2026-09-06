package com.agentflow.agent.task.sse;

import com.agentflow.agent.task.dto.TaskEventBatch;
import com.agentflow.agent.task.service.TaskEventGapException;
import com.agentflow.agent.task.service.TaskEventQueryService;
import com.agentflow.agent.task.sse.TaskSseEncoder.Frame;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

/**
 * Durable replay with bounded, nonblocking servlet output. The only cursor advanced here is
 * lastSentSequence; only the client can know its last processed sequence. No Runner interaction.
 */
@Service
public class TaskSseService {
    private final TaskEventQueryService events;
    private final TaskSseEncoder encoder;
    private final TaskSseProperties limits;
    private final ScheduledThreadPoolExecutor pollers;
    private final ScheduledThreadPoolExecutor watchdog;
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    private final Map<Long, Integer> ownerConnections = new HashMap<>();
    private int admitted;
    private boolean stopping;

    public TaskSseService(TaskEventQueryService events, TaskSseEncoder encoder, TaskSseProperties limits) {
        this.events = events;
        this.encoder = encoder;
        this.limits = limits;
        pollers = scheduler(limits.getPollWorkers(), "task-sse-poll-");
        watchdog = scheduler(1, "task-sse-watchdog-");
        watchdog.scheduleWithFixedDelay(() -> {
            long now = System.nanoTime();
            connections.forEach(connection -> connection.checkDeadline(now));
        }, 25, 25, TimeUnit.MILLISECONDS);
    }

    /** All ownership/cursor/first-batch errors occur before headers or async mode are established. */
    public void open(long userId, long taskId, long cursor,
            HttpServletRequest request, HttpServletResponse response) throws IOException {
        TaskEventBatch first;
        try {
            first = events.findOwnedBatch(userId, taskId, cursor, limits.getBatchSize());
        } catch (TaskEventGapException ex) {
            throw new BusinessException(ErrorCode.TASK_EVENT_SEQUENCE_GAP);
        }
        reserve(userId);
        Connection connection = null;
        AsyncContext async = null;
        try {
            List<Frame> frames = encoder.batch(first, limits.getMaxPendingBytes());
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("text/event-stream");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-cache, no-transform");
            response.setHeader("X-Accel-Buffering", "no");
            async = request.startAsync(request, response);
            async.setTimeout(limits.getConnectionTimeoutMs());
            connection = new Connection(userId, taskId, cursor, async, response.getOutputStream());
            // Shutdown and registration share the admission lock so no connection escapes cleanup.
            synchronized (this) {
                if (stopping) throw new IllegalStateException("SSE service is stopping");
                connections.add(connection);
            }
            connection.initialize(first, frames);
        } catch (IOException | RuntimeException ex) {
            if (connection != null) connection.close();
            else {
                release(userId);
                if (async != null) async.complete();
            }
            // Once async starts, closing the stream is the error signal; never append JSON to SSE.
            if (async == null) throw ex;
        }
    }

    public synchronized int activeConnectionCount() { return admitted; }

    private synchronized void reserve(long userId) {
        int ownerCount = ownerConnections.getOrDefault(userId, 0);
        if (stopping || admitted >= limits.getMaxConnections() || ownerCount >= limits.getMaxConnectionsPerUser()) {
            throw new BusinessException(ErrorCode.TASK_SSE_CAPACITY_EXCEEDED);
        }
        admitted++;
        ownerConnections.put(userId, ownerCount + 1);
    }

    private synchronized void release(long userId) {
        admitted--;
        ownerConnections.computeIfPresent(userId, (key, count) -> count == 1 ? null : count - 1);
    }

    @PreDestroy
    public void shutdown() {
        synchronized (this) { stopping = true; }
        connections.forEach(Connection::close);
        pollers.shutdownNow();
        watchdog.shutdownNow();
    }

    private static ScheduledThreadPoolExecutor scheduler(int workers, String prefix) {
        var scheduler = new ScheduledThreadPoolExecutor(workers,
                Thread.ofPlatform().daemon(true).name(prefix, 0).factory());
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return scheduler;
    }

    private final class Connection implements WriteListener, AsyncListener {
        private final long userId;
        private final long taskId;
        private final AsyncContext async;
        private final ServletOutputStream output;
        private final long openedAt = System.nanoTime();
        private final ArrayDeque<Frame> pending = new ArrayDeque<>();
        private ScheduledFuture<?> polling;
        private long lastSentSequence;
        private long lastWriteAt = openedAt;
        private long pendingSince;
        private int offset;
        private boolean flushPending;
        private boolean closed;
        private boolean reading;
        private boolean endAfterDrain;

        Connection(long userId, long taskId, long cursor, AsyncContext async, ServletOutputStream output) {
            this.userId = userId;
            this.taskId = taskId;
            this.lastSentSequence = cursor;
            this.async = async;
            this.output = output;
        }

        synchronized void initialize(TaskEventBatch first, List<Frame> frames) {
            if (closed) return;
            async.addListener(this);
            accept(first, frames);
            if (pending.isEmpty()) enqueue(TaskSseEncoder.comment("connected"));
            // Set the future before registering the callback, which may run immediately.
            polling = pollers.scheduleWithFixedDelay(this::poll,
                    limits.getPollIntervalMs(), limits.getPollIntervalMs(), TimeUnit.MILLISECONDS);
            output.setWriteListener(this);
        }

        private void poll() {
            long cursor;
            synchronized (this) {
                if (closed) return;
                drain();
                if (closed || reading || !pending.isEmpty() || flushPending || endAfterDrain) return;
                reading = true;
                cursor = lastSentSequence;
            }
            // The service proxy ends its read transaction before encoding, waiting or socket writes.
            try {
                TaskEventBatch batch = events.findOwnedBatch(userId, taskId, cursor, limits.getBatchSize());
                List<Frame> frames = encoder.batch(batch, limits.getMaxPendingBytes());
                synchronized (this) {
                    if (closed) return;
                    accept(batch, frames);
                    if (pending.isEmpty() && !endAfterDrain
                            && elapsed(lastWriteAt) >= limits.getHeartbeatIntervalMs()) {
                        enqueue(TaskSseEncoder.comment("heartbeat"));
                    }
                    drain();
                }
            } catch (RuntimeException ex) {
                fail(ex);
            } finally {
                synchronized (this) { reading = false; }
            }
        }

        private void accept(TaskEventBatch batch, List<Frame> frames) {
            frames.forEach(this::enqueue);
            long lastQueued = frames.isEmpty() ? lastSentSequence : frames.getLast().sequence();
            endAfterDrain = batch.status().isTerminal() && lastQueued == batch.lastEventSequence();
        }

        private void enqueue(Frame frame) {
            if (pending.isEmpty() && !flushPending) pendingSince = System.nanoTime();
            pending.addLast(frame);
        }

        /** No blocking write, DB query, or wait occurs while holding the connection monitor. */
        private synchronized void drain() {
            if (closed) return;
            try {
                while (!pending.isEmpty()) {
                    if (!output.isReady()) return;
                    Frame frame = pending.getFirst();
                    int length = Math.min(4096, frame.bytes().length - offset);
                    output.write(frame.bytes(), offset, length);
                    flushPending = true;
                    offset += length;
                    lastWriteAt = System.nanoTime();
                    pendingSince = lastWriteAt;
                    if (offset == frame.bytes().length) {
                        if (frame.sequence() != null) lastSentSequence = frame.sequence();
                        pending.removeFirst();
                        offset = 0;
                    }
                }
                if (flushPending) {
                    if (!output.isReady()) return;
                    output.flush();
                    // isReady registers a callback for the container's pending bytes as well.
                    if (!output.isReady()) return;
                    flushPending = false;
                    pendingSince = 0;
                }
                if (endAfterDrain) close();
            } catch (IOException | RuntimeException ex) {
                close();
            }
        }

        private synchronized void fail(RuntimeException failure) {
            if (closed) return;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("code", "TASK_SSE_READ_FAILED");
            data.put("lastSentSequence", lastSentSequence);
            if (failure instanceof TaskEventGapException gap) {
                data.put("code", "TASK_EVENT_SEQUENCE_GAP");
                data.put("expectedSequence", gap.expectedSequence());
                if (gap.actualSequence() != null) data.put("actualSequence", gap.actualSequence());
                data.put("lastEventSequence", gap.lastEventSequence());
            } else if (failure instanceof BusinessException business
                    && business.getErrorCode() == ErrorCode.TASK_SSE_EVENT_TOO_LARGE) {
                data.put("code", "TASK_SSE_EVENT_TOO_LARGE");
            }
            // A batch is only read after the previous one drains. Never replace partially sent data.
            Frame error = encoder.error(data);
            if (error.bytes().length > limits.getMaxPendingBytes()) { close(); return; }
            enqueue(error);
            endAfterDrain = true;
            drain();
        }

        synchronized void checkDeadline(long now) {
            if (!closed && (TimeUnit.NANOSECONDS.toMillis(now - openedAt) >= limits.getConnectionTimeoutMs()
                    || pendingSince != 0 && TimeUnit.NANOSECONDS.toMillis(now - pendingSince)
                    >= limits.getSendTimeoutMs())) close();
        }

        synchronized void close() {
            if (closed) return;
            closed = true;
            if (polling != null) polling.cancel(false);
            pending.clear();
            connections.remove(this);
            release(userId);
            try { async.complete(); } catch (IllegalStateException ignored) { /* Already completed by container. */ }
        }

        @Override public void onWritePossible() { drain(); }
        @Override public void onError(Throwable error) { close(); }
        @Override public void onComplete(AsyncEvent event) { close(); }
        @Override public void onTimeout(AsyncEvent event) { close(); }
        @Override public void onError(AsyncEvent event) { close(); }
        @Override public void onStartAsync(AsyncEvent event) { close(); }

        private long elapsed(long since) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - since); }
    }
}
