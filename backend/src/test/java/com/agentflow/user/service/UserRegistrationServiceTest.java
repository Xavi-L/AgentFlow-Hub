package com.agentflow.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.user.dto.RegisterRequest;
import com.agentflow.user.dto.RegisteredUserResponse;
import com.agentflow.user.model.AppUser;
import com.agentflow.user.repository.AppUserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 中文：不依赖真实 PostgreSQL 的 Service 单元测试。它验证业务顺序和持久化对象，
 * 实际 SQL 与 Flyway 的联调留给手动/集成验证。
 * English: Database-free service unit tests. They verify orchestration and persisted
 * objects; actual SQL and Flyway integration are verified separately.
 */
@ExtendWith(MockitoExtension.class)
class UserRegistrationServiceTest {

    @Mock
    private AppUserMapper appUserMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserRegistrationService userRegistrationService;

    @Captor
    private ArgumentCaptor<AppUser> userCaptor;

    @Test
    void shouldHashPasswordAndInsertDefaultUser() {
        RegisterRequest request = new RegisterRequest(
                "xavier_01",
                "a-long-enough-password",
                "xavier@example.com",
                "Xavier"
        );
        when(appUserMapper.selectCount(any())).thenReturn(0L);
        when(passwordEncoder.encode(request.password())).thenReturn("bcrypt-hash");
        when(appUserMapper.insert(any(AppUser.class))).thenAnswer(invocation -> {
            AppUser user = invocation.getArgument(0);
            user.setId(101L);
            return 1;
        });

        RegisteredUserResponse response = userRegistrationService.register(request);

        verify(appUserMapper).insert(userCaptor.capture());
        AppUser persistedUser = userCaptor.getValue();
        assertThat(persistedUser.getPasswordHash()).isEqualTo("bcrypt-hash");
        assertThat(persistedUser.getPasswordHash()).isNotEqualTo(request.password());
        assertThat(persistedUser.getRole()).isEqualTo("USER");
        assertThat(persistedUser.getStatus()).isEqualTo("ACTIVE");
        assertThat(response.id()).isEqualTo("101");
        assertThat(response.username()).isEqualTo("xavier_01");
        assertThat(response.email()).isEqualTo("xavier@example.com");
    }

    @Test
    void shouldRejectAnExistingUsernameBeforeHashingThePassword() {
        RegisterRequest request = new RegisterRequest(
                "xavier_01",
                "a-long-enough-password",
                null,
                "Xavier"
        );
        when(appUserMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> userRegistrationService.register(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.USER_USERNAME_ALREADY_EXISTS));

        verify(passwordEncoder, never()).encode(any());
        // 中文：BaseMapper 同时提供 insert(T) 与 insert(Collection<T>)；
        // 指定 AppUser.class 才能消除 Mockito any() 的重载歧义。
        // English: BaseMapper overloads insert for one entity and a collection, so
        // AppUser.class disambiguates Mockito's otherwise untyped any().
        verify(appUserMapper, never()).insert(any(AppUser.class));
    }

    @Test
    void shouldNormalizeABlankOptionalEmailToNull() {
        RegisterRequest request = new RegisterRequest(
                "xavier_01",
                "a-long-enough-password",
                "   ",
                "Xavier"
        );
        when(appUserMapper.selectCount(any())).thenReturn(0L);
        when(passwordEncoder.encode(request.password())).thenReturn("bcrypt-hash");
        when(appUserMapper.insert(any(AppUser.class))).thenAnswer(invocation -> {
            AppUser user = invocation.getArgument(0);
            user.setId(102L);
            return 1;
        });

        RegisteredUserResponse response = userRegistrationService.register(request);

        verify(appUserMapper).insert(userCaptor.capture());
        assertThat(userCaptor.getValue().getEmail()).isNull();
        assertThat(response.email()).isNull();
    }

    @Test
    void shouldRejectAnExistingEmailBeforeHashingThePassword() {
        RegisterRequest request = new RegisterRequest(
                "xavier_01",
                "a-long-enough-password",
                "xavier@example.com",
                "Xavier"
        );
        // First count is for username, second is for email.
        when(appUserMapper.selectCount(any())).thenReturn(0L, 1L);

        assertThatThrownBy(() -> userRegistrationService.register(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.USER_EMAIL_ALREADY_EXISTS));

        verify(passwordEncoder, never()).encode(any());
        verify(appUserMapper, never()).insert(any(AppUser.class));
    }
}
