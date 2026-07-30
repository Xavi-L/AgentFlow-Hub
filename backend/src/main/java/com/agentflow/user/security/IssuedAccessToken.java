package com.agentflow.user.security;

/**
 * 中文：JwtService 在内部传递的已签发 token，不直接作为 Controller 的 HTTP DTO。
 * English: Token issued inside JwtService. It is not exposed directly as a controller DTO.
 */
public record IssuedAccessToken(
        String value,
        long expiresIn
) {
}
