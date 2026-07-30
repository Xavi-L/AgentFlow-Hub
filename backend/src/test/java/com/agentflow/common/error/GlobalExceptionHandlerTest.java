package com.agentflow.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.common.web.TraceIdHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;

class GlobalExceptionHandlerTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @AfterEach
    void clearRequestContext() {
        TraceIdHolder.clear();
    }

    @Test
    void shouldMapBusinessExceptionToItsDeclaredHttpStatus() {
        TraceIdHolder.setTraceId("af-test-business");

        ResponseEntity<ApiResponse<Void>> response = handler.handleBusinessException(
                new BusinessException(ErrorCode.COMMON_NOT_FOUND, "Agent not found")
        );

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("COMMON_NOT_FOUND");
        assertThat(response.getBody().getMessage()).isEqualTo("Agent not found");
        assertThat(response.getBody().getTraceId()).isEqualTo("af-test-business");
    }

    @Test
    void shouldHideUnexpectedExceptionDetailsFromClient() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleException(
                new IllegalStateException("database password must not leak")
        );

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("SYS_INTERNAL_ERROR");
        assertThat(response.getBody().getMessage()).isEqualTo("Internal server error");
    }

    @Test
    void shouldMapDatabaseUniqueConstraintRacesToConflict() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleDuplicateKey(
                new DuplicateKeyException("unique constraint violated")
        );

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("USER_ACCOUNT_ALREADY_EXISTS");
        assertThat(response.getBody().getMessage())
                .isEqualTo("Username or email is already in use");
    }
}
