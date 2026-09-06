package com.agentflow.agent.task.controller;

import com.agentflow.agent.task.dto.AgentTaskResponse;
import com.agentflow.agent.task.dto.AgentTaskSummaryResponse;
import com.agentflow.agent.task.dto.CreateAgentTaskRequest;
import com.agentflow.agent.task.service.AgentTaskRestService;
import com.agentflow.agent.task.service.AgentTaskRestService.CreateTaskResponse;
import com.agentflow.agent.trace.dto.PublicTaskTraceResponse;
import com.agentflow.common.api.ApiResponse;
import com.agentflow.common.api.PageRequest;
import com.agentflow.common.api.PageResult;
import com.agentflow.user.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** V41 task REST boundary. Ownership comes exclusively from the JWT principal. */
@RestController
@RequestMapping("${agentflow.api.prefix}")
public class AgentTaskController {
    private final AgentTaskRestService taskService;

    public AgentTaskController(AgentTaskRestService taskService) {
        this.taskService = taskService;
    }

    /** The service's insertion result determines 201/200, independently of task status. */
    @PostMapping("/agents/{agentId}/tasks")
    public ResponseEntity<ApiResponse<AgentTaskResponse>> create(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @PathVariable Long agentId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateAgentTaskRequest request
    ) {
        CreateTaskResponse result = taskService.create(currentUser, agentId, idempotencyKey, request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponse.success(
                        result.created() ? "Task created" : "Task reused", result.task()));
    }

    @GetMapping("/tasks")
    public ApiResponse<PageResult<AgentTaskSummaryResponse>> list(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @ModelAttribute PageRequest pageRequest
    ) {
        return ApiResponse.success(taskService.list(currentUser, pageRequest));
    }

    @GetMapping("/tasks/{taskId}")
    public ApiResponse<AgentTaskResponse> get(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @PathVariable Long taskId
    ) {
        return ApiResponse.success("Task retrieved", taskService.get(currentUser, taskId));
    }

    /** Running cancellation may return RUNNING until the existing runner finishes cancellation. */
    @PostMapping("/tasks/{taskId}/cancel")
    public ApiResponse<AgentTaskResponse> cancel(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @PathVariable Long taskId
    ) {
        return ApiResponse.success("Task cancellation requested", taskService.cancel(currentUser, taskId));
    }

    @GetMapping("/tasks/{taskId}/trace")
    public ApiResponse<PublicTaskTraceResponse> trace(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @PathVariable Long taskId
    ) {
        return ApiResponse.success("Task trace retrieved", taskService.trace(currentUser, taskId));
    }
}
