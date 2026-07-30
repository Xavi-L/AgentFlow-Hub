package com.agentflow.user.controller;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.user.dto.CurrentUserResponse;
import com.agentflow.user.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 中文：需要已认证用户的 HTTP 入口。身份由安全过滤器建立，Controller 只读取安全的 principal。
 * English: HTTP entry point for endpoints that require authentication. The security
 * filter establishes identity; this controller reads only the safe principal.
 */
@RestController
@RequestMapping("${agentflow.api.prefix}/users")
public class UserController {

    /**
     * 中文：返回当前 token 对应、且仍在数据库中处于 ACTIVE 状态的用户摘要。
     * English: Returns the summary of the token's user, who is still ACTIVE in the database.
     */
    @GetMapping("/me")
    public ApiResponse<CurrentUserResponse> currentUser(
            @AuthenticationPrincipal AuthenticatedUser currentUser
    ) {
        return ApiResponse.success(CurrentUserResponse.from(currentUser));
    }
}
