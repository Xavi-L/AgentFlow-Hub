package com.agentflow.agent.task.dto;

import com.agentflow.agent.task.model.TaskStatus;
import java.util.List;
import java.util.Objects;

/** A bounded event page and its task state/watermark from one database snapshot. */
public record TaskEventBatch(TaskStatus status, long lastEventSequence, List<SafeTaskEventResponse> events) {
    public TaskEventBatch {
        Objects.requireNonNull(status, "status must not be null");
        events = List.copyOf(events);
    }
}
