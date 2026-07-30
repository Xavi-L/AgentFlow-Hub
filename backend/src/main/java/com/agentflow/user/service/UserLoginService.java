package com.agentflow.user.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.user.dto.CurrentUserResponse;
import com.agentflow.user.dto.LoginRequest;
import com.agentflow.user.dto.LoginResponse;
import com.agentflow.user.model.AppUser;
import com.agentflow.user.repository.AppUserMapper;
import com.agentflow.user.security.IssuedAccessToken;
import com.agentflow.user.security.JwtService;
import java.time.OffsetDateTime;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 中文：用户登录的业务编排层。它负责统一失败语义、密码哈希比对、账户状态检查、
 * 登录时间更新和 token 签发顺序。
 * English: Business orchestration for login. It owns consistent failure semantics,
 * password-hash matching, account-state checks, login-time persistence, and token
 * issuance ordering.
 */
@Service
public class UserLoginService {
    private static final String ACTIVE_STATUS = "ACTIVE";
    private static final String BEARER_TOKEN_TYPE = "Bearer";

    private final AppUserMapper appUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public UserLoginService(
            AppUserMapper appUserMapper,
            PasswordEncoder passwordEncoder,
            JwtService jwtService
    ) {
        this.appUserMapper = appUserMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    /**
     * 中文：不存在的用户和密码不匹配统一返回同一个 401，避免接口成为用户名枚举工具。
     * 密码正确后才暴露“已禁用”状态，并在签发 token 前更新登录审计时间。
     * English: A missing user and a wrong password both produce the same 401, preventing
     * the endpoint from becoming a username-enumeration oracle. Account-disabled state is
     * revealed only after a correct password, and audit timestamps are updated before a
     * token is issued.
     */
    @Transactional
    public LoginResponse login(LoginRequest request) {
        String username = request.username().trim();
        AppUser user = appUserMapper.selectOne(
                Wrappers.<AppUser>lambdaQuery()
                        .eq(AppUser::getUsername, username)
                        .isNull(AppUser::getDeletedAt)
        );

        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }
        if (!ACTIVE_STATUS.equals(user.getStatus())) {
            throw new BusinessException(ErrorCode.AUTH_ACCOUNT_DISABLED);
        }

        OffsetDateTime now = OffsetDateTime.now();
        // 中文：不要 updateById(user) 回写刚查询到的整行；新建的 patch entity 只携带本次
        // 登录应修改的两个审计字段。
        // English: Do not call updateById(user) and write the queried whole row back.
        // This patch entity carries only the two audit fields changed by this login.
        AppUser auditUpdate = new AppUser();
        auditUpdate.setLastLoginAt(now);
        auditUpdate.setUpdatedAt(now);
        int affectedRows = appUserMapper.update(
                auditUpdate,
                Wrappers.<AppUser>lambdaUpdate()
                        .eq(AppUser::getId, user.getId())
        );
        if (affectedRows != 1) {
            throw new IllegalStateException("Expected exactly one updated app_user row during login");
        }

        IssuedAccessToken accessToken = jwtService.issueAccessToken(user);
        return new LoginResponse(
                accessToken.value(),
                BEARER_TOKEN_TYPE,
                accessToken.expiresIn(),
                CurrentUserResponse.from(user)
        );
    }
}
