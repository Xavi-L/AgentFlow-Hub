package com.agentflow.knowledge.controller;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.common.api.PageRequest;
import com.agentflow.common.api.PageResult;
import com.agentflow.knowledge.dto.KnowledgeDocumentResponse;
import com.agentflow.knowledge.service.KnowledgeDocumentService;
import com.agentflow.user.security.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 中文：知识库下原始文档的 HTTP 入口。身份只从 JWT 建立的 SecurityContext 获取，路径中的
 * knowledgeBaseId 也必须由 Service 用当前 owner 再次范围查询，不能信任客户端给出的归属。
 *
 * <p>English: HTTP entry point for source documents under a knowledge base. Identity is
 * read only from the JWT-established SecurityContext, and the Service range-checks the
 * path knowledgeBaseId against the current owner rather than trusting client claims.
 */
@RestController
@RequestMapping("${agentflow.api.prefix}/knowledge-bases/{knowledgeBaseId}/documents")
public class KnowledgeDocumentController {
    private final KnowledgeDocumentService knowledgeDocumentService;

    public KnowledgeDocumentController(KnowledgeDocumentService knowledgeDocumentService) {
        this.knowledgeDocumentService = knowledgeDocumentService;
    }

    /**
     * 中文：接收一个名为 file 的 multipart part，成功时创建 PENDING 文档记录并返回 201。
     * English: Accepts a multipart part named file, creates a PENDING document record,
     * and returns 201 on success.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<KnowledgeDocumentResponse>> upload(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @PathVariable("knowledgeBaseId") Long knowledgeBaseId,
            @RequestParam(value = "file", required = false) MultipartFile file
    ) {
        KnowledgeDocumentResponse document = knowledgeDocumentService.upload(
                currentUser,
                knowledgeBaseId,
                file
        );
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("Document uploaded", document));
    }

    /**
     * 中文：分页查看当前用户在该知识库中的文档，不会返回其他用户的资源或内部物理存储位置。
     * English: Pages through the current user's documents in this knowledge base without
     * exposing other users' resources or internal physical storage locations.
     */
    @GetMapping
    public ApiResponse<PageResult<KnowledgeDocumentResponse>> list(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @PathVariable("knowledgeBaseId") Long knowledgeBaseId,
            @ModelAttribute PageRequest pageRequest
    ) {
        return ApiResponse.success(
                knowledgeDocumentService.listOwnedByKnowledgeBase(currentUser, knowledgeBaseId, pageRequest)
        );
    }
}
