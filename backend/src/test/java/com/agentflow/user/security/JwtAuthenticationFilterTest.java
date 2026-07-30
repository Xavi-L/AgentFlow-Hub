package com.agentflow.user.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentflow.common.web.TraceIdHolder;
import com.agentflow.user.service.CurrentUserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.MalformedJwtException;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 中文：过滤器测试验证 Header 到 SecurityContext 的转换，以及错误 token 的统一 JSON 响应。
 * English: Filter tests verify Header-to-SecurityContext conversion and the unified JSON
 * response for invalid tokens.
 */
class JwtAuthenticationFilterTest {

    @AfterEach
    void clearRequestState() {
        SecurityContextHolder.clearContext();
        TraceIdHolder.clear();
    }

    @Test
    void shouldPutAnActiveUserIntoSecurityContextForAValidBearerToken() throws Exception {
        JwtService jwtService = Mockito.mock(JwtService.class);
        CurrentUserService currentUserService = Mockito.mock(CurrentUserService.class);
        JwtAuthenticationFilter filter = filter(jwtService, currentUserService);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users/me");
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();
        AuthenticatedUser currentUser = new AuthenticatedUser(101L, "xavier_01", "Xavier", "USER");
        when(jwtService.parseAccessTokenSubject("valid-token")).thenReturn(101L);
        when(currentUserService.findActiveUserById(101L)).thenReturn(Optional.of(currentUser));

        filter.doFilter(request, response, filterChain);

        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.getPrincipal()).isEqualTo(currentUser);
        assertThat(authentication.getAuthorities()).extracting("authority").containsExactly("ROLE_USER");
        verify(jwtService).parseAccessTokenSubject("valid-token");
    }

    @Test
    void shouldReturnUnified401ForAnInvalidBearerToken() throws Exception {
        JwtService jwtService = Mockito.mock(JwtService.class);
        CurrentUserService currentUserService = Mockito.mock(CurrentUserService.class);
        JwtAuthenticationFilter filter = filter(jwtService, currentUserService);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users/me");
        request.addHeader("Authorization", "Bearer corrupted-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        TraceIdHolder.setTraceId("af-test-invalid-jwt");
        when(jwtService.parseAccessTokenSubject("corrupted-token"))
                .thenThrow(new MalformedJwtException("corrupted"));

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        var body = new ObjectMapper().readTree(response.getContentAsByteArray());
        assertThat(body.path("code").asText()).isEqualTo("AUTH_TOKEN_INVALID");
        assertThat(body.path("traceId").asText()).isEqualTo("af-test-invalid-jwt");
    }

    @Test
    void shouldLeaveARequestWithoutAuthorizationForSecurityToChallengeLater() throws Exception {
        JwtService jwtService = Mockito.mock(JwtService.class);
        CurrentUserService currentUserService = Mockito.mock(CurrentUserService.class);
        JwtAuthenticationFilter filter = filter(jwtService, currentUserService);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users/me");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    private static JwtAuthenticationFilter filter(
            JwtService jwtService,
            CurrentUserService currentUserService
    ) {
        RestAuthenticationEntryPoint entryPoint = new RestAuthenticationEntryPoint(
                new ObjectMapper().findAndRegisterModules()
        );
        return new JwtAuthenticationFilter(jwtService, currentUserService, entryPoint);
    }
}
