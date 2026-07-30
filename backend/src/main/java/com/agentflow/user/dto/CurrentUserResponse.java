package com.agentflow.user.dto;

import com.agentflow.user.model.AppUser;
import com.agentflow.user.security.AuthenticatedUser;

/**
 * 中文：可安全返回给前端的当前用户摘要。密码哈希、邮箱、状态和审计时间属于内部数据，
 * 本轮不通过登录响应或 {@code /users/me} 暴露。
 * English: A safe current-user summary for clients. Password hashes, email, status,
 * and audit timestamps are internal data and are not exposed by this slice.
 */
public record CurrentUserResponse(
        String id,
        String username,
        String displayName,
        String role
) {
    public static CurrentUserResponse from(AppUser user) {
        return new CurrentUserResponse(
                String.valueOf(user.getId()),
                user.getUsername(),
                user.getDisplayName(),
                user.getRole()
        );
    }

    public static CurrentUserResponse from(AuthenticatedUser user) {
        return new CurrentUserResponse(
                String.valueOf(user.id()),
                user.username(),
                user.displayName(),
                user.role()
        );
    }
}
