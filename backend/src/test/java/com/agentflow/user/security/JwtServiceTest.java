package com.agentflow.user.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentflow.user.model.AppUser;
import io.jsonwebtoken.JwtException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * 中文：直接测试 JWT 的签发和验证，不依赖 Spring 上下文或真实时间。
 * English: Tests JWT issuance and verification directly, without a Spring context or
 * the real system clock.
 */
class JwtServiceTest {
    private static final Instant ISSUED_AT = Instant.parse("2026-07-30T12:00:00Z");
    private static final String SECRET_BASE64 = Base64.getEncoder().encodeToString(
            "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8)
    );

    @Test
    void shouldIssueAndParseAnHs256AccessToken() {
        JwtService jwtService = jwtService("agentflow-hub", Clock.fixed(ISSUED_AT, ZoneOffset.UTC));
        AppUser user = new AppUser();
        user.setId(101L);

        IssuedAccessToken token = jwtService.issueAccessToken(user);

        assertThat(token.value()).isNotBlank();
        assertThat(token.expiresIn()).isEqualTo(7200);
        assertThat(jwtService.parseAccessTokenSubject(token.value())).isEqualTo(101L);
    }

    @Test
    void shouldRejectAnExpiredToken() {
        JwtService issuer = jwtService("agentflow-hub", Clock.fixed(ISSUED_AT, ZoneOffset.UTC));
        AppUser user = new AppUser();
        user.setId(101L);
        String token = issuer.issueAccessToken(user).value();
        JwtService expiredValidator = jwtService(
                "agentflow-hub",
                Clock.fixed(ISSUED_AT.plus(Duration.ofHours(3)), ZoneOffset.UTC)
        );

        assertThatThrownBy(() -> expiredValidator.parseAccessTokenSubject(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void shouldRejectATokenFromADifferentIssuer() {
        JwtService issuer = jwtService("another-service", Clock.fixed(ISSUED_AT, ZoneOffset.UTC));
        AppUser user = new AppUser();
        user.setId(101L);
        String token = issuer.issueAccessToken(user).value();
        JwtService validator = jwtService("agentflow-hub", Clock.fixed(ISSUED_AT, ZoneOffset.UTC));

        assertThatThrownBy(() -> validator.parseAccessTokenSubject(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void shouldKeepHs256WhenTheConfiguredRandomSecretIsLongerThan32Bytes() {
        String longSecret = Base64.getEncoder().encodeToString(
                "0123456789012345678901234567890101234567890123456789012345678901"
                        .getBytes(StandardCharsets.UTF_8)
        );
        JwtProperties properties = properties("agentflow-hub", longSecret);
        JwtService jwtService = new JwtService(
                properties,
                Clock.fixed(ISSUED_AT, ZoneOffset.UTC)
        );
        AppUser user = new AppUser();
        user.setId(101L);

        String token = jwtService.issueAccessToken(user).value();
        String headerJson = new String(
                Base64.getUrlDecoder().decode(token.substring(0, token.indexOf('.'))),
                StandardCharsets.UTF_8
        );

        assertThat(headerJson).contains("\"alg\":\"HS256\"");
        assertThat(jwtService.parseAccessTokenSubject(token)).isEqualTo(101L);
    }

    @Test
    void shouldFailFastWhenTheSigningSecretIsMissingOrTooShort() {
        JwtProperties missingSecret = properties("agentflow-hub", "");
        assertThatThrownBy(() -> new JwtService(missingSecret, Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET_BASE64");

        String shortSecret = Base64.getEncoder().encodeToString("too-short".getBytes(StandardCharsets.UTF_8));
        JwtProperties tooShortSecret = properties("agentflow-hub", shortSecret);
        assertThatThrownBy(() -> new JwtService(tooShortSecret, Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    private static JwtService jwtService(String issuer, Clock clock) {
        return new JwtService(properties(issuer, SECRET_BASE64), clock);
    }

    private static JwtProperties properties(String issuer, String secretBase64) {
        JwtProperties properties = new JwtProperties();
        properties.setIssuer(issuer);
        properties.setSecretBase64(secretBase64);
        properties.setAccessTokenTtl(Duration.ofHours(2));
        return properties;
    }
}
