package com.agentflow.user.dto;

/**
 * 中文：注册成功后可安全返回给客户端的用户摘要。密码哈希和内部审计字段永远不出现在 API 中。
 * English: Safe user summary returned after registration. Password hashes and internal
 * audit fields must never cross the API boundary.
 */
public record RegisteredUserResponse(
        String id,
        String username,
        String email,
        String displayName,
        String role
) {
}
