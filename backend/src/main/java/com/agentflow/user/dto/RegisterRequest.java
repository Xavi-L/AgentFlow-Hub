package com.agentflow.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 中文：公开注册接口接收的请求体。没有 id、role、status、passwordHash 等服务端控制字段，
 * 防止客户端伪造权限或绕过密码哈希。
 * English: Public registration request. Server-owned fields such as id, role, status,
 * and passwordHash are deliberately absent to prevent privilege forgery or hash bypass.
 */
public record RegisterRequest(
        @NotBlank(message = "username must not be blank")
        @Pattern(
                regexp = "^[A-Za-z0-9_]{3,64}$",
                message = "username must contain 3 to 64 letters, digits, or underscores"
        )
        String username,

        @NotBlank(message = "password must not be blank")
        @Size(min = 8, max = 72, message = "password must contain 8 to 72 characters")
        String password,

        @Email(message = "email must be valid")
        @Size(max = 128, message = "email must not exceed 128 characters")
        String email,

        @NotBlank(message = "displayName must not be blank")
        @Size(max = 64, message = "displayName must not exceed 64 characters")
        String displayName
) {
}
