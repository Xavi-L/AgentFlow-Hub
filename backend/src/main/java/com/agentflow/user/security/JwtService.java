package com.agentflow.user.security;

import com.agentflow.user.model.AppUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.io.Decoders;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

/**
 * 中文：JWT 的唯一签发和解析入口。它只处理 token 密码学与标准 claims；用户名密码校验
 * 属于 UserLoginService，HTTP Header 解析属于 JwtAuthenticationFilter。
 * English: The sole issuer and parser for JWTs. It owns token cryptography and standard
 * claims only; credential checks belong to UserLoginService and HTTP header handling
 * belongs to JwtAuthenticationFilter.
 */
@Service
public class JwtService {
    private static final int MINIMUM_HS256_KEY_BYTES = 32;
    private static final String TOKEN_TYPE_CLAIM = "token_type";
    private static final String ACCESS_TOKEN_TYPE = "access";

    private final SecretKey signingKey;
    private final String issuer;
    private final Duration accessTokenTtl;
    private final Clock clock;

    public JwtService(JwtProperties properties, Clock clock) {
        this.signingKey = signingKey(properties.getSecretBase64());
        this.issuer = requireText(properties.getIssuer(), "agentflow.security.jwt.issuer");
        this.accessTokenTtl = requirePositive(properties.getAccessTokenTtl());
        this.clock = clock;
    }

    /**
     * 中文：subject 只保存用户 ID；不把密码、密码哈希或邮箱塞进可被客户端持有的 token。
     * English: The subject contains only the user ID. Passwords, password hashes, and
     * email never go into a client-held token.
     */
    public IssuedAccessToken issueAccessToken(AppUser user) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(accessTokenTtl);
        String token = Jwts.builder()
                .issuer(issuer)
                .subject(String.valueOf(user.getId()))
                .claim(TOKEN_TYPE_CLAIM, ACCESS_TOKEN_TYPE)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
        return new IssuedAccessToken(token, accessTokenTtl.toSeconds());
    }

    /**
     * 中文：验证签名、issuer、时间边界和 token 类型后，返回可信的用户 ID。
     * English: Verifies the signature, issuer, time bounds, and token type, then returns
     * the trusted user ID.
     */
    public Long parseAccessTokenSubject(String token) {
        Claims claims = Jwts.parser()
                .clock(() -> Date.from(clock.instant()))
                .verifyWith(signingKey)
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        if (!ACCESS_TOKEN_TYPE.equals(claims.get(TOKEN_TYPE_CLAIM, String.class))) {
            throw new MalformedJwtException("JWT is not an access token");
        }

        try {
            return Long.valueOf(claims.getSubject());
        } catch (NumberFormatException ex) {
            throw new MalformedJwtException("JWT subject is not a user id");
        }
    }

    private static SecretKey signingKey(String secretBase64) {
        String configuredSecret = requireText(secretBase64, "JWT_SECRET_BASE64");
        byte[] keyBytes;
        try {
            keyBytes = Decoders.BASE64.decode(configuredSecret);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("JWT_SECRET_BASE64 must be valid Base64", ex);
        }
        if (keyBytes.length < MINIMUM_HS256_KEY_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET_BASE64 must decode to at least 32 bytes for HS256"
            );
        }
        // 中文：即使调用方提供超过 32 字节的随机密钥，也固定使用 HmacSHA256，使 JWT 头
        // 的 alg 始终和显式的 HS256 签名算法一致。
        // English: Even if a caller supplies more than 32 random bytes, retain HmacSHA256
        // so the key algorithm always agrees with the explicitly selected HS256 JWT alg.
        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    private static String requireText(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(propertyName + " must be configured");
        }
        return value.trim();
    }

    private static Duration requirePositive(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalStateException("agentflow.security.jwt.access-token-ttl must be positive");
        }
        return duration;
    }
}
