package com.agentflow.agent.controller;

import com.agentflow.agent.dto.AgentAppResponse;
import com.agentflow.agent.dto.AgentAppSummaryResponse;
import com.agentflow.agent.dto.CreateAgentAppRequest;
import com.agentflow.agent.service.AgentAppService;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP boundary for the V30 Agent root resource. */
@RestController
@RequestMapping("${agentflow.api.prefix}/agents")
public class AgentAppController {
    private final AgentAppService agentAppService;

    public AgentAppController(AgentAppService agentAppService) {
        this.agentAppService = agentAppService;
    }

    /** Creates an Agent owned by the current JWT principal and returns 201 Created. */
    @PostMapping
    public ResponseEntity<ApiResponse<AgentAppResponse>> create(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @Valid @RequestBody CreateAgentAppRequest request
    ) {
        AgentAppResponse agentApp = agentAppService.create(currentUser, request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("Agent created", agentApp));
    }

    /** Returns a compact page of the current owner's non-deleted Agent metadata. */
    @GetMapping
    public ApiResponse<PageResult<AgentAppSummaryResponse>> list(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @ModelAttribute PageRequest pageRequest
    ) {
        return ApiResponse.success(agentAppService.listOwnedBy(currentUser, pageRequest));
    }
}
