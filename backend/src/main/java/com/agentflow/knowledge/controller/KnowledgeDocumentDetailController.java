package com.agentflow.knowledge.controller;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.knowledge.dto.KnowledgeDocumentResponse;
import com.agentflow.knowledge.service.KnowledgeDocumentService;
import com.agentflow.user.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 中文：单文档安全元数据详情与失败重试的顶层 HTTP 入口。它只从 SecurityContext 读取当前用户，
 * 详情与重试成功都只返回既有 {@link KnowledgeDocumentResponse} 的安全字段；文件内容、存储
 * 定位信息和解析错误都不在此路由中公开。
 *
 * <p>English: Top-level HTTP entry point for one document's safe metadata detail and failed
 * reprocess request. It reads the current user only from SecurityContext, and both routes return
 * only the established safe fields in {@link KnowledgeDocumentResponse}; file content, storage
 * locators, and parser errors are not exposed through this route.
 */
@RestController
@RequestMapping("${agentflow.api.prefix}/documents")
public class KnowledgeDocumentDetailController {
    private final KnowledgeDocumentService knowledgeDocumentService;

    public KnowledgeDocumentDetailController(KnowledgeDocumentService knowledgeDocumentService) {
        this.knowledgeDocumentService = knowledgeDocumentService;
    }

    /**
     * 中文：读取当前 owner 可见的一条文档元数据，成功保持项目统一的 HTTP 200 + ApiResponse 外壳。
     * English: Reads one document metadata record visible to the current owner and keeps the
     * project's standard HTTP 200 + ApiResponse envelope on success.
     */
    @GetMapping("/{documentId}")
    public ApiResponse<KnowledgeDocumentResponse> get(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @PathVariable Long documentId
    ) {
        return ApiResponse.success(
                "Document retrieved",
                knowledgeDocumentService.getOwnedById(currentUser, documentId)
        );
    }

    /**
     * 中文：请求将当前 owner 可见的 FAILED 文档重新排入 PENDING。此入口没有请求体，也不会直接
     * 启动解析；既有 process-pending 流程稍后消费该 PENDING 文档。
     *
     * <p>English: Requests requeueing a visible FAILED document of the current owner as
     * PENDING. This route has no request body and does not start parsing directly; the existing
     * process-pending flow consumes the PENDING document later.
     */
    @PostMapping("/{documentId}/reprocess")
    public ApiResponse<KnowledgeDocumentResponse> reprocess(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @PathVariable Long documentId
    ) {
        return ApiResponse.success(
                "Document reprocessing requested",
                knowledgeDocumentService.reprocessOwnedFailed(currentUser, documentId)
        );
    }
}
