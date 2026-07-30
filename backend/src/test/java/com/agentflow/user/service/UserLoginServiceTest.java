package com.agentflow.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.user.dto.LoginRequest;
import com.agentflow.user.dto.LoginResponse;
import com.agentflow.user.model.AppUser;
import com.agentflow.user.repository.AppUserMapper;
import com.agentflow.user.security.IssuedAccessToken;
import com.agentflow.user.security.JwtService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 中文：登录 Service 的无数据库单元测试。它验证 BCrypt 比对、统一失败语义、状态检查、
 * 审计更新和 token 签发顺序。
 * English: Database-free unit tests for the login service. They verify BCrypt matching,
 * uniform failure semantics, state checks, audit updates, and token-issuance ordering.
 */
@ExtendWith(MockitoExtension.class)
class UserLoginServiceTest {

    @Mock
    private AppUserMapper appUserMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    @InjectMocks
    private UserLoginService userLoginService;

    @Captor
    private ArgumentCaptor<AppUser> auditUpdateCaptor;

    @Test
    void shouldIssueATokenAndUpdateAuditFieldsForAnActiveUser() {
        LoginRequest request = new LoginRequest("xavier_01", "a-long-enough-password");
        AppUser user = activeUser();
        when(appUserMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches(request.password(), user.getPasswordHash())).thenReturn(true);
        when(appUserMapper.update(any(AppUser.class), any())).thenReturn(1);
        when(jwtService.issueAccessToken(user)).thenReturn(new IssuedAccessToken("signed-token", 7200));

        LoginResponse response = userLoginService.login(request);

        assertThat(response.accessToken()).isEqualTo("signed-token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(7200);
        assertThat(response.user().id()).isEqualTo("101");
        assertThat(response.user().username()).isEqualTo("xavier_01");
        verify(appUserMapper).update(auditUpdateCaptor.capture(), any());
        assertThat(auditUpdateCaptor.getValue().getLastLoginAt()).isNotNull();
        assertThat(auditUpdateCaptor.getValue().getUpdatedAt()).isNotNull();
        verify(jwtService).issueAccessToken(user);
    }

    @Test
    void shouldUseTheSame401ForAMissingUserAndNeverIssueAToken() {
        LoginRequest request = new LoginRequest("missing_user", "a-long-enough-password");
        when(appUserMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> userLoginService.login(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS));

        verify(passwordEncoder, never()).matches(any(CharSequence.class), anyString());
        verify(jwtService, never()).issueAccessToken(any());
        verify(appUserMapper, never()).update(any(AppUser.class), any());
    }

    @Test
    void shouldUseTheSame401ForAWrongPasswordAndNeverIssueAToken() {
        LoginRequest request = new LoginRequest("xavier_01", "wrong-password");
        AppUser user = activeUser();
        when(appUserMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches(request.password(), user.getPasswordHash())).thenReturn(false);

        assertThatThrownBy(() -> userLoginService.login(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS));

        verify(jwtService, never()).issueAccessToken(any());
        verify(appUserMapper, never()).update(any(AppUser.class), any());
    }

    @Test
    void shouldRejectADisabledAccountAfterThePasswordMatches() {
        LoginRequest request = new LoginRequest("xavier_01", "a-long-enough-password");
        AppUser user = activeUser();
        user.setStatus("DISABLED");
        when(appUserMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches(request.password(), user.getPasswordHash())).thenReturn(true);

        assertThatThrownBy(() -> userLoginService.login(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_ACCOUNT_DISABLED));

        verify(jwtService, never()).issueAccessToken(any());
        verify(appUserMapper, never()).update(any(AppUser.class), any());
    }

    private static AppUser activeUser() {
        AppUser user = new AppUser();
        user.setId(101L);
        user.setUsername("xavier_01");
        user.setPasswordHash("bcrypt-hash");
        user.setDisplayName("Xavier");
        user.setRole("USER");
        user.setStatus("ACTIVE");
        return user;
    }
}
