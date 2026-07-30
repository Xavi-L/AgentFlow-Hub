package com.agentflow.user.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.user.dto.CurrentUserResponse;
import com.agentflow.user.security.AuthenticatedUser;
import org.junit.jupiter.api.Test;

class UserControllerTest {

    @Test
    void shouldReturnOnlyTheSafeCurrentUserSummary() {
        UserController controller = new UserController();

        ApiResponse<CurrentUserResponse> response = controller.currentUser(
                new AuthenticatedUser(101L, "xavier_01", "Xavier", "USER")
        );

        assertThat(response.getCode()).isEqualTo("OK");
        assertThat(response.getData()).isNotNull();
        assertThat(response.getData().id()).isEqualTo("101");
        assertThat(response.getData().username()).isEqualTo("xavier_01");
        assertThat(response.getData().role()).isEqualTo("USER");
    }
}
