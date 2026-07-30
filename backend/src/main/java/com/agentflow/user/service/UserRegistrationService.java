package com.agentflow.user.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.user.dto.RegisterRequest;
import com.agentflow.user.dto.RegisteredUserResponse;
import com.agentflow.user.model.AppUser;
import com.agentflow.user.repository.AppUserMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 中文：用户注册的业务编排层。它负责业务顺序和事务边界，Controller 不直接操作 Mapper。
 * English: Business orchestration for registration. It owns ordering and transaction
 * boundaries so controllers never access a mapper directly.
 */
@Service
public class UserRegistrationService {
    private static final String DEFAULT_ROLE = "USER";
    private static final String DEFAULT_STATUS = "ACTIVE";

    private final AppUserMapper appUserMapper;
    private final PasswordEncoder passwordEncoder;

    public UserRegistrationService(AppUserMapper appUserMapper, PasswordEncoder passwordEncoder) {
        this.appUserMapper = appUserMapper;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 中文：查重、哈希密码并写入用户。数据库唯一约束仍是并发情形下的最终保护。
     * English: Checks duplicates, hashes the password, and inserts a user. Database
     * unique constraints remain the final protection under concurrent requests.
     */
    @Transactional
    public RegisteredUserResponse register(RegisterRequest request) {
        String username = request.username().trim();
        String email = normalizeOptional(request.email());
        String displayName = request.displayName().trim();

        if (existsByUsername(username)) {
            throw new BusinessException(ErrorCode.USER_USERNAME_ALREADY_EXISTS);
        }
        if (email != null && existsByEmail(email)) {
            throw new BusinessException(ErrorCode.USER_EMAIL_ALREADY_EXISTS);
        }

        AppUser user = new AppUser();
        user.setUsername(username);
        user.setEmail(email);
        // 中文：绝不持久化明文密码；English: Never persist a plaintext password.
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setDisplayName(displayName);
        user.setRole(DEFAULT_ROLE);
        user.setStatus(DEFAULT_STATUS);

        int affectedRows = appUserMapper.insert(user);
        if (affectedRows != 1) {
            throw new IllegalStateException("Expected exactly one inserted app_user row");
        }

        // 中文：API 用字符串返回 BIGINT id，避免 JavaScript Number 精度问题。
        // English: Return BIGINT ids as strings to avoid JavaScript Number precision loss.
        return new RegisteredUserResponse(
                String.valueOf(user.getId()),
                user.getUsername(),
                user.getEmail(),
                user.getDisplayName(),
                user.getRole()
        );
    }

    private boolean existsByUsername(String username) {
        Long count = appUserMapper.selectCount(
                Wrappers.<AppUser>lambdaQuery().eq(AppUser::getUsername, username)
        );
        return count != null && count > 0;
    }

    private boolean existsByEmail(String email) {
        Long count = appUserMapper.selectCount(
                Wrappers.<AppUser>lambdaQuery().eq(AppUser::getEmail, email)
        );
        return count != null && count > 0;
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
