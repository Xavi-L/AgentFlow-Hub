package com.agentflow.user.controller;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.user.dto.LoginRequest;
import com.agentflow.user.dto.LoginResponse;
import com.agentflow.user.dto.RegisterRequest;
import com.agentflow.user.dto.RegisteredUserResponse;
import com.agentflow.user.service.UserLoginService;
import com.agentflow.user.service.UserRegistrationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 中文：认证相关的 HTTP 入口。本轮开放注册和登录；JWT 校验发生在安全过滤器中，
 * Controller 本身不接触密码哈希或 token 签名细节。
 * English: HTTP entry point for authentication. This slice exposes registration and
 * login; JWT verification lives in the security filter, so the controller never
 * handles password hashes or token-signing details.
 */
@RestController
@RequestMapping("${agentflow.api.prefix}/auth")
public class AuthController {
    private final UserRegistrationService userRegistrationService;
    private final UserLoginService userLoginService;

    public AuthController(
            UserRegistrationService userRegistrationService,
            UserLoginService userLoginService
    ) {
        this.userRegistrationService = userRegistrationService;
        this.userLoginService = userLoginService;
    }

    /**
     * 中文：创建一个新用户。@Valid 在进入业务层前检查 DTO 标注的字段规则。
     * English: Creates a new user. @Valid enforces DTO field rules before business logic.
     */
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<RegisteredUserResponse>> register(
            @Valid @RequestBody RegisterRequest request
    ) {
        RegisteredUserResponse registeredUser = userRegistrationService.register(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("User registered", registeredUser));
    }

    /**
     * 中文：校验用户名和密码，并在成功后返回短期 Bearer access token。
     * English: Validates credentials and returns a short-lived Bearer access token on success.
     */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.success("Login successful", userLoginService.login(request));
    }
}
