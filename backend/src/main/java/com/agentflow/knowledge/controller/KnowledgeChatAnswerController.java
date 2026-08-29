package com.agentflow.knowledge.controller;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.common.api.PageRequest;
import com.agentflow.common.api.PageResult;
import com.agentflow.knowledge.dto.ChatTestRequest;
import com.agentflow.knowledge.dto.KnowledgeChatAnswerFeedbackCoverageItemResponse;
import com.agentflow.knowledge.dto.KnowledgeChatAnswerFeedbackCoverageResponse;
import com.agentflow.knowledge.dto.KnowledgeChatAnswerFeedbackRequest;
import com.agentflow.knowledge.dto.KnowledgeChatAnswerFeedbackResponse;
import com.agentflow.knowledge.dto.KnowledgeChatAnswerFeedbackSummaryResponse;
import com.agentflow.knowledge.dto.KnowledgeChatAnswerFeedbackStatusResponse;
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

/** V10/V11 immutable answer audit routes plus V12 feedback and V13–V17 read-only views. */
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

    @GetMapping("/chat-answers/{answerId}/feedback")
    public ApiResponse<KnowledgeChatAnswerFeedbackStatusResponse> getFeedbackStatus(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @PathVariable("knowledgeBaseId") Long knowledgeBaseId,
            @PathVariable("answerId") Long answerId
    ) {
        return ApiResponse.success(
                "Knowledge chat answer feedback status retrieved",
                knowledgeChatAnswerFeedbackService.getFeedbackStatus(
                        currentUser,
                        knowledgeBaseId,
                        answerId
                )
        );
    }

    @GetMapping("/chat-answer-feedbacks")
    public ApiResponse<PageResult<KnowledgeChatAnswerFeedbackResponse>> listFeedbacks(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @PathVariable("knowledgeBaseId") Long knowledgeBaseId,
            @ModelAttribute PageRequest pageRequest
    ) {
        return ApiResponse.success(
                "Knowledge chat answer feedback ledger retrieved",
                knowledgeChatAnswerFeedbackService.listOwnedByKnowledgeBase(
                        currentUser,
                        knowledgeBaseId,
                        pageRequest
                )
        );
    }

    @GetMapping("/chat-answer-feedbacks/summary")
    public ApiResponse<KnowledgeChatAnswerFeedbackSummaryResponse> getFeedbackSummary(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @PathVariable("knowledgeBaseId") Long knowledgeBaseId
    ) {
        return ApiResponse.success(
                "Knowledge chat answer feedback summary retrieved",
                knowledgeChatAnswerFeedbackService.getSummaryOwnedByKnowledgeBase(
                        currentUser,
                        knowledgeBaseId
                )
        );
    }

    @GetMapping("/chat-answer-feedbacks/coverage")
    public ApiResponse<KnowledgeChatAnswerFeedbackCoverageResponse> getFeedbackCoverage(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @PathVariable("knowledgeBaseId") Long knowledgeBaseId
    ) {
        return ApiResponse.success(
                "Knowledge chat answer feedback coverage retrieved",
                knowledgeChatAnswerFeedbackService.getCoverageOwnedByKnowledgeBase(
                        currentUser,
                        knowledgeBaseId
                )
        );
    }

    @GetMapping("/chat-answer-feedbacks/coverage-ledger")
    public ApiResponse<PageResult<KnowledgeChatAnswerFeedbackCoverageItemResponse>>
            listFeedbackCoverageLedger(
                    @AuthenticationPrincipal AuthenticatedUser currentUser,
                    @PathVariable("knowledgeBaseId") Long knowledgeBaseId,
                    @ModelAttribute PageRequest pageRequest
            ) {
        return ApiResponse.success(
                "Knowledge chat answer feedback coverage ledger retrieved",
                knowledgeChatAnswerFeedbackService.listCoverageLedgerOwnedByKnowledgeBase(
                        currentUser,
                        knowledgeBaseId,
                        pageRequest
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
