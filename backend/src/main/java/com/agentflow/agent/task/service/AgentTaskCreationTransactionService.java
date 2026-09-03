package com.agentflow.agent.task.service;

import com.agentflow.agent.snapshot.AgentTaskExecutionSnapshot;
import com.agentflow.agent.snapshot.AgentTaskSnapshotResolver;
import com.agentflow.agent.task.dispatch.AfterCommitTaskDispatchCoordinator;
import com.agentflow.agent.task.model.AgentTask;
import com.agentflow.agent.task.model.TaskEventType;
import com.agentflow.agent.task.model.TaskStatus;
import com.agentflow.agent.task.model.TokenUsageQuality;
import com.agentflow.agent.task.repository.AgentTaskMapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Owns the one repeatable-read transaction that freezes and inserts a new task. */
@Service
public class AgentTaskCreationTransactionService {
    private final AgentTaskSnapshotResolver snapshotResolver;
    private final AgentTaskMapper taskMapper;
    private final TaskEventAppender eventAppender;
    private final AfterCommitTaskDispatchCoordinator dispatchCoordinator;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AgentTaskCreationTransactionService(
            AgentTaskSnapshotResolver snapshotResolver,
            AgentTaskMapper taskMapper,
            TaskEventAppender eventAppender,
            AfterCommitTaskDispatchCoordinator dispatchCoordinator,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.snapshotResolver = Objects.requireNonNull(snapshotResolver, "snapshotResolver must not be null");
        this.taskMapper = Objects.requireNonNull(taskMapper, "taskMapper must not be null");
        this.eventAppender = Objects.requireNonNull(eventAppender, "eventAppender must not be null");
        this.dispatchCoordinator = Objects.requireNonNull(
                dispatchCoordinator,
                "dispatchCoordinator must not be null"
        );
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public AgentTask createNew(CreateAgentTaskCommand command, String requestFingerprint) {
        AgentTaskExecutionSnapshot snapshot = snapshotResolver.resolve(command.userId(), command.agentId());
        requireMatchingSnapshot(command, snapshot);

        OffsetDateTime createdAt = OffsetDateTime.now(clock);
        AgentTask task = new AgentTask();
        task.setId(IdWorker.getId());
        task.setUserId(command.userId());
        task.setAgentId(command.agentId());
        task.setClientRequestId(command.clientRequestId());
        task.setRequestFingerprint(requestFingerprint);
        task.setStatus(TaskStatus.QUEUED.name());
        task.setUserInput(command.userInput());
        task.setExecutionSnapshot(toJson(snapshot));
        task.setMaxDecisionTurns(snapshot.agent().maxDecisionTurns());
        task.setMaxToolCalls(snapshot.agent().maxToolCalls());
        task.setMaxTotalTokens(snapshot.agent().maxTotalTokens());
        task.setReservedFinalTokens(reservedFinalTokens(snapshot.agent().maxTotalTokens()));
        task.setDecisionTurnsUsed(0);
        task.setToolCallsUsed(0);
        task.setInputTokens(0);
        task.setOutputTokens(0);
        task.setTotalTokens(0);
        task.setTokenUsageQuality(TokenUsageQuality.UNKNOWN.name());
        task.setCitations("[]");
        task.setLastEventSequence(0L);
        task.setCreatedAt(createdAt);
        task.setUpdatedAt(createdAt);
        task.setVersion(0);

        if (taskMapper.insertTask(task) != 1) {
            throw new IllegalStateException("Expected exactly one Agent task to be inserted");
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("status", TaskStatus.QUEUED.name());
        task.setLastEventSequence(eventAppender.append(task.getId(), TaskEventType.TASK_CREATED, payload));
        dispatchCoordinator.dispatchAfterCommit(task.getId());
        return task;
    }

    static int reservedFinalTokens(int maxTotalTokens) {
        return Math.min(2048, Math.max(1, maxTotalTokens / 4));
    }

    private static void requireMatchingSnapshot(
            CreateAgentTaskCommand command,
            AgentTaskExecutionSnapshot snapshot
    ) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        if (!Long.toString(command.agentId()).equals(snapshot.agent().agentId())
                || !"ACTIVE".equals(snapshot.agent().status())) {
            throw new IllegalStateException("Resolved execution snapshot does not match the task command");
        }
    }

    private String toJson(AgentTaskExecutionSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Execution snapshot could not be serialized", ex);
        }
    }
}
