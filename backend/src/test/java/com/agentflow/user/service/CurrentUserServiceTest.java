package com.agentflow.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.agentflow.user.model.AppUser;
import com.agentflow.user.repository.AppUserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CurrentUserServiceTest {

    @Mock
    private AppUserMapper appUserMapper;

    @InjectMocks
    private CurrentUserService currentUserService;

    @Test
    void shouldExposeOnlyAnActiveNonDeletedUserToTheSecurityLayer() {
        AppUser user = new AppUser();
        user.setId(101L);
        user.setUsername("xavier_01");
        user.setDisplayName("Xavier");
        user.setRole("USER");
        when(appUserMapper.selectOne(any())).thenReturn(user);

        var currentUser = currentUserService.findActiveUserById(101L);

        assertThat(currentUser).isPresent();
        assertThat(currentUser.orElseThrow().id()).isEqualTo(101L);
        assertThat(currentUser.orElseThrow().role()).isEqualTo("USER");
    }

    @Test
    void shouldReturnEmptyWhenTheSecurityQueryFindsNoActiveUser() {
        when(appUserMapper.selectOne(any())).thenReturn(null);

        assertThat(currentUserService.findActiveUserById(101L)).isEmpty();
    }
}
