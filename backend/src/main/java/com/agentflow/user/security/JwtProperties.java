package com.agentflow.user.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 中文：JWT 的非业务配置。密钥只从环境变量进入配置绑定，绝不能写入 YAML 或提交到 Git。
 * English: Non-business JWT configuration. The signing secret enters only through an
 * environment variable and must never be written to YAML or committed to Git.
 */
@ConfigurationProperties(prefix = "agentflow.security.jwt")
public class JwtProperties {
    private String issuer = "agentflow-hub";
    private String secretBase64;
    private Duration accessTokenTtl = Duration.ofHours(2);

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getSecretBase64() {
        return secretBase64;
    }

    public void setSecretBase64(String secretBase64) {
        this.secretBase64 = secretBase64;
    }

    public Duration getAccessTokenTtl() {
        return accessTokenTtl;
    }

    public void setAccessTokenTtl(Duration accessTokenTtl) {
        this.accessTokenTtl = accessTokenTtl;
    }
}
