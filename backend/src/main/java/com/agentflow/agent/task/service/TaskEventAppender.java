package com.agentflow.agent.task.service;

import com.agentflow.agent.task.model.AgentTaskEvent;
import com.agentflow.agent.task.model.TaskEventType;
import com.agentflow.agent.task.repository.AgentTaskEventMapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** The sole allocator for task-local event sequences. Callers must own a short transaction. */
@Service
public class TaskEventAppender {
    private final AgentTaskEventMapper eventMapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public TaskEventAppender(
            AgentTaskEventMapper eventMapper,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.eventMapper = Objects.requireNonNull(eventMapper, "eventMapper must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public long append(long taskId, TaskEventType eventType, JsonNode payload) {
        if (taskId <= 0) {
            throw new IllegalArgumentException("taskId must be positive");
        }
        Objects.requireNonNull(eventType, "eventType must not be null");
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("event payload must be an object");
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Task events must be appended inside a database transaction");
        }

        Long sequence = eventMapper.incrementAndGetSequence(taskId);
        if (sequence == null || sequence <= 0) {
            throw new IllegalStateException("Task event sequence could not be allocated");
        }

        AgentTaskEvent event = new AgentTaskEvent();
        event.setId(IdWorker.getId());
        event.setTaskId(taskId);
        event.setSequenceNo(sequence);
        event.setEventType(eventType.name());
        event.setPayload(toJson(payload));
        event.setCreatedAt(OffsetDateTime.now(clock));
        if (eventMapper.insertEvent(event) != 1) {
            throw new IllegalStateException("Expected exactly one task event to be inserted");
        }
        return sequence;
    }

    private String toJson(JsonNode payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Task event payload could not be serialized", ex);
        }
    }
}
