package com.agentflow.knowledge.controller;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.common.api.PageRequest;
import com.agentflow.common.api.PageResult;
import com.agentflow.knowledge.dto.CreateKnowledgeBaseRequest;
import com.agentflow.knowledge.dto.KnowledgeBaseResponse;
import com.agentflow.knowledge.dto.UpdateKnowledgeBaseMetadataRequest;
import com.agentflow.knowledge.service.KnowledgeBaseService;
import com.agentflow.user.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 中文：知识库元数据的 HTTP 入口。鉴权由已存在的 SecurityConfig 和 JWT filter 完成；本类只从
 * 安全上下文读取 principal，绝不从 JSON 读取 userId。
 *
 * <p>English: HTTP entry point for knowledge-base metadata. Existing SecurityConfig and
 * JWT filtering authenticate the request; this class reads the principal only from
 * SecurityContext and never from a JSON userId.
 */
@RestController
@RequestMapping("${agentflow.api.prefix}/knowledge-bases")
public class KnowledgeBaseController {
    private final KnowledgeBaseService knowledgeBaseService;

    public KnowledgeBaseController(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    /**
     * 中文：创建当前登录用户拥有的知识库，成功时返回 201 Created。
     * English: Creates a knowledge base owned by the current user and returns 201 Created.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<KnowledgeBaseResponse>> create(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @Valid @RequestBody CreateKnowledgeBaseRequest request
    ) {
        KnowledgeBaseResponse knowledgeBase = knowledgeBaseService.create(currentUser, request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("Knowledge base created", knowledgeBase));
    }

    /**
     * 中文：分页查看当前用户的知识库；PageRequest 会把不安全的 page/pageSize 自动收敛到范围内。
     * English: Lists the current user's knowledge bases by page; PageRequest normalizes
     * unsafe page/pageSize values into the shared safe range.
     */
    @GetMapping
    public ApiResponse<PageResult<KnowledgeBaseResponse>> list(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @ModelAttribute PageRequest pageRequest
    ) {
        return ApiResponse.success(knowledgeBaseService.listOwnedBy(currentUser, pageRequest));
    }

    /**
     * 中文：读取当前登录用户自己、且尚未软删除的一条知识库元数据。路径 ID 和 JWT principal
     * 都交由 Service 固定到同一条查询中，不先按 ID 单独查找，避免泄露其他 owner 的资源存在性。
     *
     * <p>English: Reads one non-deleted knowledge-base metadata record owned by the current
     * user. The path ID and JWT principal remain in the same Service query instead of an
     * ID-only pre-read, preventing disclosure that another owner's resource exists.
     */
    @GetMapping("/{knowledgeBaseId}")
    public ApiResponse<KnowledgeBaseResponse> get(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @PathVariable Long knowledgeBaseId
    ) {
        return ApiResponse.success(
                "Knowledge base retrieved",
                knowledgeBaseService.getOwnedById(currentUser, knowledgeBaseId)
        );
    }

    /**
     * 中文：当前 owner 只能部分修改名称和描述。请求 DTO 的局部严格反序列化器拒绝所有其他
     * JSON 字段；owner、状态和 embedding/chunk 配置永远不从请求体取得。
     *
     * <p>English: The current owner can partially update only name and description. The
     * request DTO's local strict deserializer rejects every other JSON field; owner,
     * status, and embedding/chunk settings never come from the request body.
     */
    @PatchMapping("/{knowledgeBaseId}")
    public ApiResponse<KnowledgeBaseResponse> updateMetadata(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @PathVariable Long knowledgeBaseId,
            @Valid @RequestBody UpdateKnowledgeBaseMetadataRequest request
    ) {
        return ApiResponse.success(
                "Knowledge base updated",
                knowledgeBaseService.updateMetadata(currentUser, knowledgeBaseId, request)
        );
    }
}
