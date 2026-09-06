package com.agentflow.agent.task.service;

import com.agentflow.agent.task.dto.SafeTaskEventProjector;
import com.agentflow.agent.task.dto.SafeTaskEventResponse;
import com.agentflow.agent.task.dto.TaskEventBatch;
import com.agentflow.agent.task.model.AgentTask;
import com.agentflow.agent.task.model.AgentTaskEvent;
import com.agentflow.agent.task.model.TaskStatus;
import com.agentflow.agent.task.repository.AgentTaskEventMapper;
import com.agentflow.agent.task.repository.AgentTaskMapper;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Owner-scoped safe event replay. Each read ends its transaction before transport sends or waits. */
@Service
public class TaskEventQueryService {
    public static final int MAX_BATCH_SIZE = 256;
    private final AgentTaskMapper taskMapper;
    private final AgentTaskEventMapper eventMapper;
    private final SafeTaskEventProjector projector;

    public TaskEventQueryService(
            AgentTaskMapper taskMapper, AgentTaskEventMapper eventMapper, SafeTaskEventProjector projector
    ) {
        this.taskMapper = taskMapper;
        this.eventMapper = eventMapper;
        this.projector = projector;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<SafeTaskEventResponse> findOwnedEventsAfter(long userId, long taskId, long afterSequence) {
        if (userId <= 0 || taskId <= 0 || afterSequence < 0) {
            throw new IllegalArgumentException("Positive user/task IDs and a non-negative cursor are required");
        }
        if (taskMapper.selectOwnedById(taskId, userId) == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND, "Agent task not found");
        }
        return eventMapper.selectByTaskIdAfterSequence(taskId, afterSequence).stream()
                .map(projector::toResponse).toList();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TaskEventBatch findOwnedBatch(long userId, long taskId, long afterSequence, int limit) {
        // Only the historical task owner controls visibility; source resources may have been deleted.
        AgentTask task = taskMapper.selectOwnedById(taskId, userId);
        if (task == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND, "Agent task not found");
        }
        long watermark = task.getLastEventSequence();
        if (afterSequence < 0 || afterSequence > watermark) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID,
                    "Event cursor must be between zero and the current event sequence");
        }
        if (limit < 1 || limit > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("Event batch size must be between 1 and " + MAX_BATCH_SIZE);
        }
        List<AgentTaskEvent> events = eventMapper.selectBatchByTaskIdAfterSequence(
                taskId, afterSequence, watermark, limit);
        long previous = afterSequence;
        for (AgentTaskEvent event : events) {
            // previous cannot overflow: a row is valid only while previous is below the watermark.
            if (previous == Long.MAX_VALUE || event.getSequenceNo() != previous + 1
                    || event.getSequenceNo() > watermark) {
                throw new TaskEventGapException(previous == Long.MAX_VALUE ? previous : previous + 1,
                        event.getSequenceNo(), watermark);
            }
            previous = event.getSequenceNo();
        }
        if (events.size() < limit && previous < watermark) {
            throw new TaskEventGapException(previous + 1, null, watermark);
        }
        return new TaskEventBatch(TaskStatus.valueOf(task.getStatus()), watermark,
                events.stream().map(projector::toResponse).toList());
    }
}
