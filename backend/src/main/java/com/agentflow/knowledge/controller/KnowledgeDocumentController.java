package com.agentflow.knowledge.controller;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.common.api.PageRequest;
import com.agentflow.common.api.PageResult;
import com.agentflow.knowledge.dto.DocumentProcessingResponse;
import com.agentflow.knowledge.dto.KnowledgeChunkResponse;
import com.agentflow.knowledge.dto.KnowledgeDocumentResponse;
import com.agentflow.knowledge.service.DocumentProcessingService;
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
    private final DocumentProcessingService documentProcessingService;

    public KnowledgeDocumentController(
            KnowledgeDocumentService knowledgeDocumentService,
            DocumentProcessingService documentProcessingService
    ) {
        this.knowledgeDocumentService = knowledgeDocumentService;
        this.documentProcessingService = documentProcessingService;
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

    /**
     * 中文：同步处理该知识库中当前的 PENDING 文档。这个显式入口便于观察 V4 的状态机；它不创建
     * 队列任务，也不接 embedding 或 Qdrant。
     *
     * <p>English: Synchronously processes the knowledge base's current PENDING
     * documents. This explicit route makes V4's state machine observable; it creates
     * no queue task and connects neither embeddings nor Qdrant.
     */
    @PostMapping("/process-pending")
    public ApiResponse<DocumentProcessingResponse> processPending(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @PathVariable("knowledgeBaseId") Long knowledgeBaseId
    ) {
        return ApiResponse.success(
                "Pending documents processed",
                documentProcessingService.processPending(currentUser, knowledgeBaseId)
        );
    }

    /**
     * 中文：按稳定的 chunkIndex 升序分页查看当前 owner 的文本块，用于直接验收解析与 overlap 结果。
     * English: Pages through the current owner's chunks in stable chunkIndex order for
     * direct verification of parsing and overlap results.
     */
    @GetMapping("/{documentId}/chunks")
    public ApiResponse<PageResult<KnowledgeChunkResponse>> listChunks(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @PathVariable("knowledgeBaseId") Long knowledgeBaseId,
            @PathVariable("documentId") Long documentId,
            @ModelAttribute PageRequest pageRequest
    ) {
        return ApiResponse.success(documentProcessingService.listOwnedDocumentChunks(
                currentUser,
                knowledgeBaseId,
                documentId,
                pageRequest
        ));
    }
}
