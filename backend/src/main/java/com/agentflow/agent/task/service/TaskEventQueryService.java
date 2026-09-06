package com.agentflow.agent.task.service;

import com.agentflow.agent.task.dto.SafeTaskEventProjector;
import com.agentflow.agent.task.dto.SafeTaskEventResponse;
import com.agentflow.agent.task.repository.AgentTaskEventMapper;
import com.agentflow.agent.task.repository.AgentTaskMapper;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Owner-scoped safe event replay seam. V41 deliberately has no subscription endpoint. */
@Service
public class TaskEventQueryService {
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
}
