package com.agentflow.knowledge.controller;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.knowledge.dto.ChunkVectorizationResponse;
import com.agentflow.knowledge.service.ChunkVectorizationService;
import com.agentflow.user.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Explicit owner-scoped HTTP entry point for the V5 synchronous vectorization stage. */
@RestController
@RequestMapping("${agentflow.api.prefix}/knowledge-bases/{knowledgeBaseId}/chunks")
public class KnowledgeChunkController {
    private final ChunkVectorizationService chunkVectorizationService;

    public KnowledgeChunkController(ChunkVectorizationService chunkVectorizationService) {
        this.chunkVectorizationService = chunkVectorizationService;
    }

    /**
     * Does not create a queue task: it synchronously vectorizes only still-PENDING
     * chunks belonging to completed source documents in this owner-scoped knowledge base.
     */
    @PostMapping("/vectorize-pending")
    public ApiResponse<ChunkVectorizationResponse> vectorizePending(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @PathVariable("knowledgeBaseId") Long knowledgeBaseId
    ) {
        return ApiResponse.success(
                "Pending chunks vectorized",
                chunkVectorizationService.vectorizePending(currentUser, knowledgeBaseId)
        );
    }
}
