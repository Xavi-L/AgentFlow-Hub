package com.agentflow.agent.task.service;

import com.agentflow.agent.task.execution.TaskExecutionOutcome;
import com.agentflow.agent.task.execution.TaskExecutionResultType;
import com.agentflow.agent.task.execution.TaskTokenUsage;
import com.agentflow.agent.task.model.AgentTask;
import com.agentflow.agent.task.model.TaskEventType;
import com.agentflow.agent.task.model.TaskPhase;
import com.agentflow.agent.task.model.TaskStatus;
import com.agentflow.agent.task.repository.AgentTaskMapper;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Short, independent task state transitions paired atomically with their durable events. */
@Service
public class AgentTaskLifecycleTransactionService {
    private static final int ANSWER_EVENT_MAX_BYTES = 16 * 1024;
    private final AgentTaskMapper taskMapper;
    private final TaskEventAppender eventAppender;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AgentTaskLifecycleTransactionService(
            AgentTaskMapper taskMapper,
            TaskEventAppender eventAppender,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.taskMapper = Objects.requireNonNull(taskMapper, "taskMapper must not be null");
        this.eventAppender = Objects.requireNonNull(eventAppender, "eventAppender must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AgentTask claim(long taskId) {
        OffsetDateTime startedAt = now();
        AgentTask task = taskMapper.claimQueued(taskId, startedAt);
        if (task == null) {
            return null;
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("status", TaskStatus.RUNNING.name());
        payload.put("phase", TaskPhase.PREPARING.name());
        task.setLastEventSequence(eventAppender.append(taskId, TaskEventType.TASK_STARTED, payload));
        return task;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean changePhase(long taskId, TaskPhase phase) {
        Objects.requireNonNull(phase, "phase must not be null");
        if (taskMapper.changeRunningPhase(taskId, phase.name(), now()) != 1) {
            return false;
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("phase", phase.name());
        eventAppender.append(taskId, TaskEventType.PHASE_CHANGED, payload);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markDispatchRejected(long taskId) {
        if (taskMapper.failQueuedDispatch(taskId, now()) != 1) {
            return false;
        }
        ObjectNode payload = terminalPayload(TaskStatus.FAILED, "SYSTEM_ERROR");
        payload.put("errorCode", "TASK_DISPATCH_REJECTED");
        eventAppender.append(taskId, TaskEventType.TASK_FAILED, payload);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AgentTask requestCancellation(long userId, long taskId) {
        if (userId <= 0 || taskId <= 0) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "userId and taskId must be positive");
        }
        OffsetDateTime requestedAt = now();
        AgentTask cancelled = taskMapper.cancelQueuedOwned(taskId, userId, requestedAt);
        if (cancelled != null) {
            ObjectNode payload = terminalPayload(TaskStatus.CANCELLED, "USER_CANCELLED");
            cancelled.setLastEventSequence(
                    eventAppender.append(taskId, TaskEventType.TASK_CANCELLED, payload)
            );
            return cancelled;
        }

        AgentTask running = taskMapper.requestRunningCancellationOwned(taskId, userId, requestedAt);
        if (running != null) {
            return running;
        }

        AgentTask current = taskMapper.selectOwnedById(taskId, userId);
        if (current == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND, "Agent task not found");
        }
        return current;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean complete(long taskId, TaskExecutionOutcome outcome) {
        requireResultType(outcome, TaskExecutionResultType.COMPLETED);
        TaskTokenUsage usage = outcome.tokenUsage();
        int affected = taskMapper.completeRunning(
                taskId,
                outcome.terminationReason().name(),
                outcome.decisionTurnsUsed(),
                outcome.toolCallsUsed(),
                usage.inputTokens(),
                usage.outputTokens(),
                usage.totalTokens(),
                usage.quality().name(),
                outcome.finalAnswer(),
                toJson(outcome.citations()),
                now()
        );
        if (affected != 1) {
            return false;
        }
        appendAnswerChunks(taskId, outcome.finalAnswer());
        eventAppender.append(
                taskId,
                TaskEventType.TASK_COMPLETED,
                terminalPayload(TaskStatus.COMPLETED, outcome.terminationReason().name())
        );
        return true;
    }

    /** All chunks are part of the same complete() transaction as the answer and terminal event. */
    private void appendAnswerChunks(long taskId, String answer) {
        int offset = 0;
        int chunkIndex = 0;
        while (offset < answer.length()) {
            StringBuilder chunk = new StringBuilder();
            // Exact serialized JSON measurement includes escaping, UTF-8 and event metadata.
            int bytes = serializedByteSize(answerChunk(chunkIndex, ""));
            while (offset < answer.length()) {
                int codePoint = answer.codePointAt(offset);
                String character = new String(Character.toChars(codePoint));
                int encodedBytes = serializedByteSize(character) - 2;
                if (bytes + encodedBytes > ANSWER_EVENT_MAX_BYTES) {
                    break;
                }
                chunk.append(character);
                bytes += encodedBytes;
                offset += Character.charCount(codePoint);
            }
            if (chunk.isEmpty()) {
                throw new IllegalStateException("Answer character exceeds event payload limit");
            }
            eventAppender.append(taskId, TaskEventType.ANSWER_CHUNK,
                    answerChunk(chunkIndex++, chunk.toString()));
        }
    }

    private int serializedByteSize(Object value) {
        try {
            return objectMapper.writeValueAsBytes(value).length;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Answer event could not be serialized", ex);
        }
    }

    private ObjectNode answerChunk(int index, String text) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("chunkIndex", index);
        payload.put("text", text);
        return payload;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean fail(long taskId, TaskExecutionOutcome outcome) {
        requireResultType(outcome, TaskExecutionResultType.FAILED);
        TaskTokenUsage usage = outcome.tokenUsage();
        int affected = taskMapper.failRunning(
                taskId,
                outcome.terminationReason().name(),
                outcome.decisionTurnsUsed(),
                outcome.toolCallsUsed(),
                usage.inputTokens(),
                usage.outputTokens(),
                usage.totalTokens(),
                usage.quality().name(),
                outcome.errorCode(),
                outcome.errorMessage(),
                now()
        );
        if (affected != 1) {
            return false;
        }
        ObjectNode payload = terminalPayload(TaskStatus.FAILED, outcome.terminationReason().name());
        payload.put("errorCode", outcome.errorCode());
        eventAppender.append(taskId, TaskEventType.TASK_FAILED, payload);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean timeOut(long taskId, TaskExecutionOutcome outcome) {
        requireResultType(outcome, TaskExecutionResultType.TIMED_OUT);
        TaskTokenUsage usage = outcome.tokenUsage();
        int affected = taskMapper.timeOutRunning(
                taskId,
                outcome.decisionTurnsUsed(),
                outcome.toolCallsUsed(),
                usage.inputTokens(),
                usage.outputTokens(),
                usage.totalTokens(),
                usage.quality().name(),
                now()
        );
        if (affected != 1) {
            return false;
        }
        eventAppender.append(
                taskId,
                TaskEventType.TASK_TIMED_OUT,
                terminalPayload(TaskStatus.TIMED_OUT, "DEADLINE_EXCEEDED")
        );
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean finishCancellation(long taskId, TaskExecutionOutcome outcome) {
        requireResultType(outcome, TaskExecutionResultType.CANCELLED);
        TaskTokenUsage usage = outcome.tokenUsage();
        int affected = taskMapper.finishRunningCancellation(
                taskId,
                outcome.decisionTurnsUsed(),
                outcome.toolCallsUsed(),
                usage.inputTokens(),
                usage.outputTokens(),
                usage.totalTokens(),
                usage.quality().name(),
                now()
        );
        if (affected != 1) {
            return false;
        }
        eventAppender.append(
                taskId,
                TaskEventType.TASK_CANCELLED,
                terminalPayload(TaskStatus.CANCELLED, "USER_CANCELLED")
        );
        return true;
    }

    private static void requireResultType(
            TaskExecutionOutcome outcome,
            TaskExecutionResultType expected
    ) {
        Objects.requireNonNull(outcome, "outcome must not be null");
        if (outcome.resultType() != expected) {
            throw new IllegalArgumentException("Expected a " + expected + " execution outcome");
        }
    }

    private ObjectNode terminalPayload(TaskStatus status, String terminationReason) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("status", status.name());
        payload.put("terminationReason", terminationReason);
        return payload;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Task result JSON could not be serialized", ex);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
