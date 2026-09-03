package com.agentflow.agent.task.service;

import com.agentflow.agent.task.model.AgentTask;
import com.agentflow.agent.task.repository.AgentTaskMapper;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Independent reads used after failed writes and between Runner transaction boundaries. */
@Service
public class AgentTaskQueryService {
    private final AgentTaskMapper taskMapper;

    public AgentTaskQueryService(AgentTaskMapper taskMapper) {
        this.taskMapper = Objects.requireNonNull(taskMapper, "taskMapper must not be null");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public AgentTask findByUserAndClientRequestId(long userId, String clientRequestId) {
        return taskMapper.selectByUserAndClientRequestId(userId, clientRequestId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public AgentTask findById(long taskId) {
        return taskMapper.selectById(taskId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean hasCancellationRequest(long taskId) {
        AgentTask task = taskMapper.selectById(taskId);
        return task != null && task.getCancelRequestedAt() != null;
    }
}
