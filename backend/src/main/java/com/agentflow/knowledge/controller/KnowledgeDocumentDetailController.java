package com.agentflow.knowledge.controller;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.knowledge.dto.KnowledgeDocumentResponse;
import com.agentflow.knowledge.service.KnowledgeDocumentService;
import com.agentflow.user.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 中文：单文档安全元数据详情的顶层 HTTP 入口。它只从 SecurityContext 读取当前用户，并且只
 * 返回既有 {@link KnowledgeDocumentResponse} 的安全字段；文件内容、存储定位信息和解析错误
 * 都不在此路由中公开。
 *
 * <p>English: Top-level HTTP entry point for one document's safe metadata detail. It reads
 * the current user only from SecurityContext and returns only the established safe fields in
 * {@link KnowledgeDocumentResponse}; file content, storage locators, and parser errors are
 * not exposed through this route.
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
}
