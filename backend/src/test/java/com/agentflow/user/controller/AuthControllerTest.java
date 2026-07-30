package com.agentflow.user.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentflow.common.error.GlobalExceptionHandler;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.common.web.TraceIdFilter;
import com.agentflow.user.dto.CurrentUserResponse;
import com.agentflow.user.dto.LoginResponse;
import com.agentflow.user.dto.RegisteredUserResponse;
import com.agentflow.user.service.UserLoginService;
import com.agentflow.user.service.UserRegistrationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 中文：Controller 测试聚焦 HTTP 契约：状态码、JSON 响应外壳和 Bean Validation。
 * English: Controller tests focus on the HTTP contract: status, JSON envelope, and
 * Bean Validation.
 */
class AuthControllerTest {

    @Test
    void shouldCreateAUserWithTheUnifiedResponseEnvelope() throws Exception {
        UserRegistrationService service = org.mockito.Mockito.mock(UserRegistrationService.class);
        when(service.register(any())).thenReturn(new RegisteredUserResponse(
                "101", "xavier_01", "xavier@example.com", "Xavier", "USER"
        ));
        MockMvc mockMvc = mockMvc(service);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "xavier_01",
                                  "password": "a-long-enough-password",
                                  "email": "xavier@example.com",
                                  "displayName": "Xavier"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").value("User registered"))
                .andExpect(jsonPath("$.data.id").value("101"))
                .andExpect(jsonPath("$.data.username").value("xavier_01"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
    }

    @Test
    void shouldRejectAnInvalidRegistrationRequestBeforeCallingTheService() throws Exception {
        UserRegistrationService service = org.mockito.Mockito.mock(UserRegistrationService.class);
        MockMvc mockMvc = mockMvc(service);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "x",
                                  "password": "short",
                                  "email": "not-an-email",
                                  "displayName": ""
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_PARAM_INVALID"));
    }

    @Test
    void shouldRejectMalformedJsonAsAClientRequestError() throws Exception {
        UserRegistrationService service = org.mockito.Mockito.mock(UserRegistrationService.class);
        MockMvc mockMvc = mockMvc(service);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_REQUEST_BODY_INVALID"));
    }

    @Test
    void shouldExposeAUsernameConflictAs409InsteadOf500() throws Exception {
        UserRegistrationService service = org.mockito.Mockito.mock(UserRegistrationService.class);
        when(service.register(any())).thenThrow(
                new BusinessException(ErrorCode.USER_USERNAME_ALREADY_EXISTS)
        );
        MockMvc mockMvc = mockMvc(service);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "xavier_01",
                                  "password": "a-long-enough-password",
                                  "displayName": "Xavier"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_USERNAME_ALREADY_EXISTS"));
    }

    @Test
    void shouldReturnAnAccessTokenThroughTheUnifiedLoginEnvelope() throws Exception {
        UserRegistrationService registrationService = org.mockito.Mockito.mock(UserRegistrationService.class);
        UserLoginService loginService = org.mockito.Mockito.mock(UserLoginService.class);
        when(loginService.login(any())).thenReturn(new LoginResponse(
                "signed-access-token", "Bearer", 7200,
                new CurrentUserResponse("101", "xavier_01", "Xavier", "USER")
        ));
        MockMvc mockMvc = mockMvc(registrationService, loginService);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "xavier_01",
                                  "password": "a-long-enough-password"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").value("Login successful"))
                .andExpect(jsonPath("$.data.accessToken").value("signed-access-token"))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(7200))
                .andExpect(jsonPath("$.data.user.username").value("xavier_01"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
    }

    @Test
    void shouldRejectInvalidLoginInputBeforeCallingTheService() throws Exception {
        UserRegistrationService registrationService = org.mockito.Mockito.mock(UserRegistrationService.class);
        UserLoginService loginService = org.mockito.Mockito.mock(UserLoginService.class);
        MockMvc mockMvc = mockMvc(registrationService, loginService);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "",
                                  "password": ""
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_PARAM_INVALID"));
    }

    @Test
    void shouldExposeInvalidCredentialsAs401() throws Exception {
        UserRegistrationService registrationService = org.mockito.Mockito.mock(UserRegistrationService.class);
        UserLoginService loginService = org.mockito.Mockito.mock(UserLoginService.class);
        when(loginService.login(any())).thenThrow(new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS));
        MockMvc mockMvc = mockMvc(registrationService, loginService);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "xavier_01",
                                  "password": "a-long-enough-password"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
    }

    private static MockMvc mockMvc(UserRegistrationService service) {
        return mockMvc(service, org.mockito.Mockito.mock(UserLoginService.class));
    }

    private static MockMvc mockMvc(
            UserRegistrationService registrationService,
            UserLoginService loginService
    ) {
        return MockMvcBuilders
                .standaloneSetup(new AuthController(registrationService, loginService))
                .addPlaceholderValue("agentflow.api.prefix", "/api/v1")
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new TraceIdFilter())
                .build();
    }
}
