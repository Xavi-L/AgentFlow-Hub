package com.agentflow.agent.task.service;

import com.agentflow.agent.task.dto.AgentTaskResponse;
import com.agentflow.agent.task.dto.AgentTaskResponseMapper;
import com.agentflow.agent.task.dto.AgentTaskSummaryResponse;
import com.agentflow.agent.task.dto.CreateAgentTaskRequest;
import com.agentflow.agent.task.model.AgentTask;
import com.agentflow.agent.task.repository.AgentTaskMapper;
import com.agentflow.agent.trace.TaskTraceQueryService;
import com.agentflow.agent.trace.dto.PublicTaskTraceResponse;
import com.agentflow.common.api.PageRequest;
import com.agentflow.common.api.PageResult;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.user.security.AuthenticatedUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Owner-scoped public reads and thin adapters to the existing creation/cancellation lifecycle. */
@Service
public class AgentTaskRestService {
    private final AgentTaskApplicationService application;
    private final AgentTaskLifecycleTransactionService lifecycle;
    private final AgentTaskMapper tasks;
    private final AgentTaskResponseMapper responses;
    private final TaskTraceQueryService traces;

    public AgentTaskRestService(AgentTaskApplicationService application,
            AgentTaskLifecycleTransactionService lifecycle, AgentTaskMapper tasks,
            AgentTaskResponseMapper responses, TaskTraceQueryService traces) {
        this.application = application;
        this.lifecycle = lifecycle;
        this.tasks = tasks;
        this.responses = responses;
        this.traces = traces;
    }

    public CreateTaskResponse create(AuthenticatedUser user, Long agentId,
            String idempotencyKey, CreateAgentTaskRequest request) {
        long userId = ownerId(user);
        positiveId(agentId, "agentId");
        if (request == null) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "request must not be null");
        }
        CreateAgentTaskResult result = application.createTaskWithResult(new CreateAgentTaskCommand(
                userId, agentId, idempotencyKey, request.userInput()));
        return new CreateTaskResponse(responses.toResponse(result.task()), result.created());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true,
            isolation = Isolation.REPEATABLE_READ)
    public PageResult<AgentTaskSummaryResponse> list(AuthenticatedUser user, PageRequest pageRequest) {
        long userId = ownerId(user);
        PageRequest page = pageRequest == null ? new PageRequest() : pageRequest;
        // Use long arithmetic so even the largest bound HTTP page cannot wrap its offset.
        long offset = (long) (page.getPage() - 1) * page.getPageSize();
        var items = tasks.selectOwnedPage(userId, page.getPageSize(), offset).stream()
                .map(AgentTaskSummaryResponse::from).toList();
        return PageResult.of(items, page.getPage(), page.getPageSize(), tasks.countOwned(userId));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public AgentTaskResponse get(AuthenticatedUser user, Long taskId) {
        long userId = ownerId(user);
        positiveId(taskId, "taskId");
        AgentTask task = tasks.selectOwnedById(taskId, userId);
        if (task == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND, "Agent task not found");
        }
        return responses.toResponse(task);
    }

    public AgentTaskResponse cancel(AuthenticatedUser user, Long taskId) {
        long userId = ownerId(user);
        positiveId(taskId, "taskId");
        return responses.toResponse(lifecycle.requestCancellation(userId, taskId));
    }

    public PublicTaskTraceResponse trace(AuthenticatedUser user, Long taskId) {
        long userId = ownerId(user);
        positiveId(taskId, "taskId");
        return traces.findOwnedPublicTrace(userId, taskId);
    }

    private static long ownerId(AuthenticatedUser user) {
        if (user == null || user.id() == null || user.id() <= 0) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHENTICATED, "Authentication required");
        }
        return user.id();
    }

    private static void positiveId(Long id, String label) {
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, label + " must be positive");
        }
    }

    public record CreateTaskResponse(AgentTaskResponse task, boolean created) { }
}
