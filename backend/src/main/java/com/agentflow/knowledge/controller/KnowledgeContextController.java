package com.agentflow.knowledge.controller;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.knowledge.dto.KnowledgeContextResponse;
import com.agentflow.knowledge.dto.RetrieveContextTestRequest;
import com.agentflow.knowledge.service.KnowledgeContextService;
import com.agentflow.user.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Explicit V8 owner-scoped context assembly endpoint; it does not generate an answer. */
@RestController
@RequestMapping("${agentflow.api.prefix}/knowledge-bases/{knowledgeBaseId}")
public class KnowledgeContextController {
    private final KnowledgeContextService knowledgeContextService;

    public KnowledgeContextController(KnowledgeContextService knowledgeContextService) {
        this.knowledgeContextService = knowledgeContextService;
    }

    @PostMapping("/retrieve-context-test")
    public ApiResponse<KnowledgeContextResponse> retrieveContextTest(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @PathVariable("knowledgeBaseId") Long knowledgeBaseId,
            @Valid @RequestBody RetrieveContextTestRequest request
    ) {
        return ApiResponse.success(
                "Knowledge context assembled",
                knowledgeContextService.retrieveContextTest(currentUser, knowledgeBaseId, request)
        );
    }
}
