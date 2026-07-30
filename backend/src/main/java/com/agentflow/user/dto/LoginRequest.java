package com.agentflow.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 中文：公开登录接口接收的凭据。它只接受身份标识和明文密码，不能由客户端传入
 * role、status、token 或任何数据库字段。
 * English: Credentials accepted by the public login API. Clients may provide only an
 * identity and plaintext password, never roles, status, tokens, or database fields.
 */
public record LoginRequest(
        @NotBlank(message = "username must not be blank")
        @Size(max = 64, message = "username must not exceed 64 characters")
        String username,

        @NotBlank(message = "password must not be blank")
        @Size(max = 72, message = "password must not exceed 72 characters")
        String password
) {
}
