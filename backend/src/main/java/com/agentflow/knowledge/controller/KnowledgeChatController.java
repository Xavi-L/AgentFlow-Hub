package com.agentflow.knowledge.controller;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.knowledge.dto.ChatTestRequest;
import com.agentflow.knowledge.dto.KnowledgeChatResponse;
import com.agentflow.knowledge.service.KnowledgeChatService;
import com.agentflow.user.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Explicit V9 owner-scoped, single-turn RAG answer endpoint; it has no session or streaming state. */
@RestController
@RequestMapping("${agentflow.api.prefix}/knowledge-bases/{knowledgeBaseId}")
public class KnowledgeChatController {
    private final KnowledgeChatService knowledgeChatService;

    public KnowledgeChatController(KnowledgeChatService knowledgeChatService) {
        this.knowledgeChatService = knowledgeChatService;
    }

    @PostMapping("/chat-test")
    public ApiResponse<KnowledgeChatResponse> chatTest(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @PathVariable("knowledgeBaseId") Long knowledgeBaseId,
            @Valid @RequestBody ChatTestRequest request
    ) {
        return ApiResponse.success(
                "Knowledge chat answer generated",
                knowledgeChatService.chatTest(currentUser, knowledgeBaseId, request)
        );
    }
}
