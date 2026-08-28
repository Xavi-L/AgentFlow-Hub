package com.agentflow.knowledge.controller;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.common.api.PageRequest;
import com.agentflow.common.api.PageResult;
import com.agentflow.knowledge.dto.ChatTestRequest;
import com.agentflow.knowledge.dto.KnowledgeChatAnswerFeedbackRequest;
import com.agentflow.knowledge.dto.KnowledgeChatAnswerFeedbackResponse;
import com.agentflow.knowledge.dto.KnowledgeChatAnswerResponse;
import com.agentflow.knowledge.dto.KnowledgeChatAnswerSummaryResponse;
import com.agentflow.knowledge.service.KnowledgeChatAnswerService;
import com.agentflow.knowledge.service.KnowledgeChatAnswerFeedbackService;
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

/** V10/V11 immutable answer audit routes plus V12 one-time binary feedback submission. */
@RestController
@RequestMapping("${agentflow.api.prefix}/knowledge-bases/{knowledgeBaseId}")
public class KnowledgeChatAnswerController {
    private final KnowledgeChatAnswerService knowledgeChatAnswerService;
    private final KnowledgeChatAnswerFeedbackService knowledgeChatAnswerFeedbackService;

    public KnowledgeChatAnswerController(
            KnowledgeChatAnswerService knowledgeChatAnswerService,
            KnowledgeChatAnswerFeedbackService knowledgeChatAnswerFeedbackService
    ) {
        this.knowledgeChatAnswerService = knowledgeChatAnswerService;
        this.knowledgeChatAnswerFeedbackService = knowledgeChatAnswerFeedbackService;
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

    @PostMapping("/chat-answers/{answerId}/feedback")
    public ApiResponse<KnowledgeChatAnswerFeedbackResponse> submitFeedback(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @PathVariable("knowledgeBaseId") Long knowledgeBaseId,
            @PathVariable("answerId") Long answerId,
            @Valid @RequestBody KnowledgeChatAnswerFeedbackRequest request
    ) {
        return ApiResponse.success(
                "Knowledge chat answer feedback recorded",
                knowledgeChatAnswerFeedbackService.submitFeedback(
                        currentUser,
                        knowledgeBaseId,
                        answerId,
                        request
                )
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
