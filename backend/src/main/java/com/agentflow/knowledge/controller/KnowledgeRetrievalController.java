package com.agentflow.knowledge.controller;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.knowledge.dto.KnowledgeRetrievalResponse;
import com.agentflow.knowledge.dto.RetrieveTestRequest;
import com.agentflow.knowledge.service.KnowledgeRetrievalService;
import com.agentflow.user.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Explicit, owner-scoped V7 endpoint for semantic retrieval verification. */
@RestController
@RequestMapping("${agentflow.api.prefix}/knowledge-bases/{knowledgeBaseId}")
public class KnowledgeRetrievalController {
    private final KnowledgeRetrievalService knowledgeRetrievalService;

    public KnowledgeRetrievalController(KnowledgeRetrievalService knowledgeRetrievalService) {
        this.knowledgeRetrievalService = knowledgeRetrievalService;
    }

    @PostMapping("/retrieve-test")
    public ApiResponse<KnowledgeRetrievalResponse> retrieveTest(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @PathVariable("knowledgeBaseId") Long knowledgeBaseId,
            @Valid @RequestBody RetrieveTestRequest request
    ) {
        return ApiResponse.success(
                "Knowledge base retrieved",
                knowledgeRetrievalService.retrieveTest(currentUser, knowledgeBaseId, request)
        );
    }
}
