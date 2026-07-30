package com.agentflow.user.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.agentflow.user.model.AppUser;
import com.agentflow.user.repository.AppUserMapper;
import com.agentflow.user.security.AuthenticatedUser;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 中文：在每个带 JWT 的请求中，按数据库的当前状态确认用户仍可用。这样账号被禁用或
 * 软删除后，不必等旧 token 过期才能拒绝访问；以后若有性能需要可在此处加缓存。
 * English: Confirms the database's current account state for every JWT-authenticated
 * request. A disabled or soft-deleted account is rejected without waiting for an old
 * token to expire; a future cache can be introduced here if performance requires it.
 */
@Service
public class CurrentUserService {
    private static final String ACTIVE_STATUS = "ACTIVE";

    private final AppUserMapper appUserMapper;

    public CurrentUserService(AppUserMapper appUserMapper) {
        this.appUserMapper = appUserMapper;
    }

    @Transactional(readOnly = true)
    public Optional<AuthenticatedUser> findActiveUserById(Long userId) {
        AppUser user = appUserMapper.selectOne(
                Wrappers.<AppUser>lambdaQuery()
                        .eq(AppUser::getId, userId)
                        .eq(AppUser::getStatus, ACTIVE_STATUS)
                        .isNull(AppUser::getDeletedAt)
        );
        return Optional.ofNullable(user).map(AuthenticatedUser::from);
    }
}
