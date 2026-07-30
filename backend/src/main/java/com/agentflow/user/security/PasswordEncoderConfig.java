package com.agentflow.user.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 中文：集中提供密码编码器。BCrypt 每次编码都会生成随机 salt，因而同一明文不会得到固定哈希。
 * English: Central password-encoder configuration. BCrypt generates a random salt per
 * encoding, so the same plaintext never has a fixed hash.
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
