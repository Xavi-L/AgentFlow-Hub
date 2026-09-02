package com.agentflow.agent.binding.controller;

import com.agentflow.agent.binding.dto.AgentKnowledgeBindingsResponse;
import com.agentflow.agent.binding.dto.AgentToolBindingsResponse;
import com.agentflow.agent.binding.dto.ReplaceAgentKnowledgeBindingsRequest;
import com.agentflow.agent.binding.dto.ReplaceAgentToolBindingsRequest;
import com.agentflow.agent.binding.service.AgentBindingService;
import com.agentflow.common.api.ApiResponse;
import com.agentflow.user.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP boundary for V37's knowledge-base and tool binding replacement APIs. */
@RestController
@RequestMapping("${agentflow.api.prefix}/agents/{agentId}")
public class AgentBindingController {
    private final AgentBindingService bindingService;

    public AgentBindingController(AgentBindingService bindingService) {
        this.bindingService = bindingService;
    }

    @GetMapping("/knowledge-bases")
    public ApiResponse<AgentKnowledgeBindingsResponse> getKnowledgeBindings(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @PathVariable Long agentId
    ) {
        return ApiResponse.success(bindingService.getKnowledgeBindings(currentUser, agentId));
    }

    @PutMapping("/knowledge-bases")
    public ApiResponse<AgentKnowledgeBindingsResponse> replaceKnowledgeBindings(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @PathVariable Long agentId,
            @Valid @RequestBody ReplaceAgentKnowledgeBindingsRequest request
    ) {
        return ApiResponse.success(
                "Agent knowledge bindings replaced",
                bindingService.replaceKnowledgeBindings(currentUser, agentId, request)
        );
    }

    @GetMapping("/tools")
    public ApiResponse<AgentToolBindingsResponse> getToolBindings(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @PathVariable Long agentId
    ) {
        return ApiResponse.success(bindingService.getToolBindings(currentUser, agentId));
    }

    @PutMapping("/tools")
    public ApiResponse<AgentToolBindingsResponse> replaceToolBindings(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @PathVariable Long agentId,
            @Valid @RequestBody ReplaceAgentToolBindingsRequest request
    ) {
        return ApiResponse.success(
                "Agent tool bindings replaced",
                bindingService.replaceToolBindings(currentUser, agentId, request)
        );
    }
}
