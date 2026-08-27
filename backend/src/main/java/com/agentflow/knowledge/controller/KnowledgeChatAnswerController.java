package com.agentflow.knowledge.controller;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.common.api.PageRequest;
import com.agentflow.common.api.PageResult;
import com.agentflow.knowledge.dto.ChatTestRequest;
import com.agentflow.knowledge.dto.KnowledgeChatAnswerResponse;
import com.agentflow.knowledge.dto.KnowledgeChatAnswerSummaryResponse;
import com.agentflow.knowledge.service.KnowledgeChatAnswerService;
import com.agentflow.user.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** V10 immutable answer audit creation/detail and V11 owner-scoped audit-ledger pagination. */
@RestController
@RequestMapping("${agentflow.api.prefix}/knowledge-bases/{knowledgeBaseId}")
public class KnowledgeChatAnswerController {
    private final KnowledgeChatAnswerService knowledgeChatAnswerService;

    public KnowledgeChatAnswerController(KnowledgeChatAnswerService knowledgeChatAnswerService) {
        this.knowledgeChatAnswerService = knowledgeChatAnswerService;
    }

    @PostMapping("/chat")
    public ApiResponse<KnowledgeChatAnswerResponse> chat(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @PathVariable("knowledgeBaseId") Long knowledgeBaseId,
            @Valid @RequestBody ChatTestRequest request
    ) {
        return ApiResponse.success(
                "Knowledge chat answer generated and audited",
                knowledgeChatAnswerService.chat(currentUser, knowledgeBaseId, request)
        );
    }

    @GetMapping("/chat-answers/{answerId}")
    public ApiResponse<KnowledgeChatAnswerResponse> getById(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @PathVariable("knowledgeBaseId") Long knowledgeBaseId,
            @PathVariable("answerId") Long answerId
    ) {
        return ApiResponse.success(
                "Knowledge chat answer retrieved",
                knowledgeChatAnswerService.getById(currentUser, knowledgeBaseId, answerId)
        );
    }

    @GetMapping("/chat-answers")
    public ApiResponse<PageResult<KnowledgeChatAnswerSummaryResponse>> list(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @PathVariable("knowledgeBaseId") Long knowledgeBaseId,
            @ModelAttribute PageRequest pageRequest
    ) {
        return ApiResponse.success(
                knowledgeChatAnswerService.listOwnedByKnowledgeBase(
                        currentUser,
                        knowledgeBaseId,
                        pageRequest
                )
        );
    }
}
