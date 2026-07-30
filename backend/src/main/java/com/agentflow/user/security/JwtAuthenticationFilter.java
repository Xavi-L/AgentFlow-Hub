package com.agentflow.user.security;

import com.agentflow.user.service.CurrentUserService;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 中文：从 Authorization: Bearer 头提取 token，验证其密码学有效性，再从数据库确认账号仍为
 * ACTIVE，最后把安全的 AuthenticatedUser 放入 SecurityContext。
 * English: Extracts a token from the Authorization: Bearer header, verifies its
 * cryptographic validity, confirms the account is still ACTIVE in the database, then
 * puts a safe AuthenticatedUser into SecurityContext.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final CurrentUserService currentUserService;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;

    public JwtAuthenticationFilter(
            JwtService jwtService,
            CurrentUserService currentUserService,
            RestAuthenticationEntryPoint authenticationEntryPoint
    ) {
        this.jwtService = jwtService;
        this.currentUserService = currentUserService;
        this.authenticationEntryPoint = authenticationEntryPoint;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String authorization = request.getHeader(AUTHORIZATION_HEADER);
        if (authorization == null || authorization.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!hasBearerPrefix(authorization)) {
            rejectInvalidToken(response);
            return;
        }

        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            rejectInvalidToken(response);
            return;
        }

        try {
            Long userId = jwtService.parseAccessTokenSubject(token);
            AuthenticatedUser currentUser = currentUserService.findActiveUserById(userId).orElse(null);
            if (currentUser == null) {
                rejectInvalidToken(response);
                return;
            }

            // 中文：只信任数据库刚读取的角色，而不把 token 中的旧角色当作权限来源。
            // English: Trust only the role freshly loaded from the database, not a stale
            // role claim that may be present in a previously issued token.
            var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + currentUser.role()));
            var authentication = UsernamePasswordAuthenticationToken.authenticated(
                    currentUser,
                    null,
                    authorities
            );
            SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
            securityContext.setAuthentication(authentication);
            SecurityContextHolder.setContext(securityContext);
            filterChain.doFilter(request, response);
        } catch (JwtException | IllegalArgumentException ex) {
            SecurityContextHolder.clearContext();
            rejectInvalidToken(response);
        }
    }

    private void rejectInvalidToken(HttpServletResponse response) throws IOException {
        SecurityContextHolder.clearContext();
        authenticationEntryPoint.writeInvalidToken(response);
    }

    private static boolean hasBearerPrefix(String authorization) {
        return authorization.length() > BEARER_PREFIX.length()
                && authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length());
    }
}
