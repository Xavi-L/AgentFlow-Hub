package com.agentflow.user.security;

import com.agentflow.user.model.AppUser;

/**
 * 中文：JWT 已验证、且当前仍处于 ACTIVE 状态的用户在 SecurityContext 中的表示。
 * 这不是 Entity，因此不会携带 passwordHash 等敏感持久化字段。
 * English: An active user whose JWT was verified, represented in SecurityContext. This is
 * not an entity, so persistence-only sensitive fields such as passwordHash never travel
 * through the request security context.
 */
public record AuthenticatedUser(
        Long id,
        String username,
        String displayName,
        String role
) {
    public static AuthenticatedUser from(AppUser user) {
        return new AuthenticatedUser(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getRole()
        );
    }
}
