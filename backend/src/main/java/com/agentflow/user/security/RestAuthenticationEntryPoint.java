package com.agentflow.user.security;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.common.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * 中文：Spring Security 过滤器链产生的 401 不会进入 GlobalExceptionHandler，因此在这里
 * 主动复用项目统一的 ApiResponse 外壳。
 * English: A 401 raised inside Spring Security's filter chain never reaches
 * GlobalExceptionHandler, so this component deliberately reuses the project's unified
 * ApiResponse envelope.
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {
    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authenticationException
    ) throws IOException {
        writeError(response, ErrorCode.AUTH_UNAUTHENTICATED);
    }

    /**
     * 中文：过滤器可以在 token 格式、签名或过期校验失败时直接调用这一方法。
     * English: The JWT filter calls this directly for malformed, invalid-signature, or
     * expired-token failures.
     */
    public void writeInvalidToken(HttpServletResponse response) throws IOException {
        writeError(response, ErrorCode.AUTH_TOKEN_INVALID);
    }

    private void writeError(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        response.setStatus(errorCode.getHttpStatus());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.fail(errorCode));
    }
}
