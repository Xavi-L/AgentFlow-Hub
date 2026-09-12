package com.agentflow.agent.configversion;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.common.api.PageRequest;
import com.agentflow.common.api.PageResult;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.user.security.AuthenticatedUser;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("${agentflow.api.prefix}/agents/{agentId}/config-versions")
public class AgentConfigVersionController {
    private final AgentConfigVersionService service;
    private final AgentConfigVersionTransactions reads;
    public AgentConfigVersionController(AgentConfigVersionService service, AgentConfigVersionTransactions reads) {
        this.service = service; this.reads = reads;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AgentConfigVersionResponse>> publish(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long agentId,
            @RequestBody PublishAgentConfigVersionRequest request) {
        if (request == null)
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "Publication body must be an empty object");
        var result = service.publish(owner(user), agentId);
        return ResponseEntity.status(result.created() ? 201 : 200)
                .body(ApiResponse.success(result.created() ? "Configuration published" : "Configuration reused", result.version()));
    }

    @GetMapping
    public ApiResponse<PageResult<AgentConfigVersionResponse>> list(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long agentId, @ModelAttribute PageRequest page) {
        return ApiResponse.success(reads.list(owner(user), agentId, page));
    }

    @GetMapping("/{configVersionId}")
    public ApiResponse<AgentConfigVersionResponse> get(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long agentId, @PathVariable Long configVersionId) {
        return ApiResponse.success(reads.get(owner(user), agentId, configVersionId));
    }

    /** This new API freezes invalid publication shapes as COMMON_PARAM_INVALID; other DTOs keep their contract. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> invalidPublicationBody(HttpMessageNotReadableException ignored) {
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.COMMON_PARAM_INVALID,
                "Publication body must be one empty object"));
    }

    private static long owner(AuthenticatedUser user) {
        if (user == null || user.id() == null || user.id() <= 0)
            throw new BusinessException(ErrorCode.AUTH_UNAUTHENTICATED, "Authentication required");
        return user.id();
    }
}
