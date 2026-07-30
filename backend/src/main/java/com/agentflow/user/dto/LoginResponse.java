package com.agentflow.user.dto;

/**
 * 中文：登录成功的安全响应。{@code expiresIn} 的单位固定为秒，调用方应把
 * {@code accessToken} 放入后续请求的 {@code Authorization: Bearer ...} 头中。
 * English: Safe login-success response. {@code expiresIn} is always measured in seconds;
 * callers send {@code accessToken} in later {@code Authorization: Bearer ...} headers.
 */
public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        CurrentUserResponse user
) {
}
