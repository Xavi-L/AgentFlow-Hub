package com.agentflow.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentflow.common.error.ErrorCode;
import com.agentflow.common.web.TraceIdHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ApiResponseTest {

    @AfterEach
    void clearRequestContext() {
        TraceIdHolder.clear();
    }

    @Test
    void shouldCaptureTraceIdWhenResponseIsCreated() {
        TraceIdHolder.setTraceId("af-test-001");

        ApiResponse<String> response = ApiResponse.success("payload");

        assertThat(response.getCode()).isEqualTo("OK");
        assertThat(response.getMessage()).isEqualTo("success");
        assertThat(response.getData()).isEqualTo("payload");
        assertThat(response.getTraceId()).isEqualTo("af-test-001");
        assertThat(response.getTimestamp()).isNotNull();
    }

    @Test
    void shouldCreateFailureFromErrorCodeAndCustomMessage() {
        ApiResponse<Void> response = ApiResponse.fail(
                ErrorCode.COMMON_NOT_FOUND,
                "Knowledge base not found"
        );

        assertThat(response.getCode()).isEqualTo("COMMON_NOT_FOUND");
        assertThat(response.getMessage()).isEqualTo("Knowledge base not found");
        assertThat(response.getData()).isNull();
    }
}
